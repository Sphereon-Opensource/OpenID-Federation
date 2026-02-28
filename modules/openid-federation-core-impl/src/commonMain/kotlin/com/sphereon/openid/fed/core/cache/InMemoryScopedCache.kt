package com.sphereon.openid.fed.core.cache

import com.sphereon.core.api.cache.CacheBackend
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory implementation of ScopedCache using IDK's CacheBackend for TTL
 * management and eviction, with a side map for type-safe value storage.
 *
 * The CacheBackend (provided by IDK's lib-core-api-default) handles:
 * - TTL-based expiration
 * - LRU eviction when max size is reached
 * - Pattern-based key deletion for scope clearing
 *
 * The side map (valueStore) holds the actual typed values since CacheBackend
 * operates on byte arrays and we want to avoid serialization overhead for
 * in-memory caching.
 *
 * This implementation maintains separate key prefixes for each scope:
 * - `{namespace}::APP::{key}` for APP scope
 * - `{namespace}::TENANT::{tenantId}::{key}` for TENANT scope
 * - `{namespace}::PRINCIPAL::{principalId}::{key}` for PRINCIPAL scope
 *
 * @param K The type of cache keys
 * @param V The type of cached values
 * @param namespace The cache namespace for key partitioning
 * @param backend The IDK CacheBackend for TTL and eviction management
 * @param ttlConfig TTL configuration per scope level
 */
class InMemoryScopedCache<K : Any, V : Any>(
    override val namespace: String,
    private val backend: CacheBackend,
    private val ttlConfig: CacheTtlConfig = CacheTtlConfig.DEFAULT
) : ScopedCache<K, V> {

    // Type-safe value storage (backend only stores markers for TTL tracking)
    private val valueStore = mutableMapOf<String, V>()
    private val valueLock = Mutex()

    // Statistics counters
    private var hits: Long = 0
    private var misses: Long = 0
    private var evictions: Long = 0
    private val statsLock = Mutex()

    // Marker bytes stored in CacheBackend for TTL tracking
    private val MARKER = ByteArray(1) { 1 }

    // ========== Key construction ==========

    private fun appKey(key: K): String = "$namespace::APP::$key"
    private fun tenantKey(tenantId: String, key: K): String = "$namespace::TENANT::$tenantId::$key"
    private fun principalKey(principalId: String, key: K): String = "$namespace::PRINCIPAL::$principalId::$key"

    private suspend fun recordHit() = statsLock.withLock { hits++ }
    private suspend fun recordMiss() = statsLock.withLock { misses++ }

    // ========== Internal operations ==========

    private suspend fun internalGet(cacheKey: String): V? {
        // Check if backend still has the key (handles TTL expiry and LRU eviction)
        if (backend.exists(cacheKey)) {
            val value = valueLock.withLock { valueStore[cacheKey] }
            if (value != null) {
                recordHit()
                return value
            }
        }
        // Key expired/evicted in backend or missing from value store — clean up
        valueLock.withLock { valueStore.remove(cacheKey) }
        recordMiss()
        return null
    }

    private suspend fun internalPut(cacheKey: String, value: V, ttlMs: Long): V? {
        val previous = valueLock.withLock { valueStore[cacheKey] }
        backend.set(cacheKey, MARKER, ttlMs)
        valueLock.withLock { valueStore[cacheKey] = value }
        return previous
    }

    private suspend fun internalRemove(cacheKey: String): V? {
        val previous = valueLock.withLock { valueStore.remove(cacheKey) }
        backend.delete(cacheKey)
        return previous
    }

    private suspend fun internalGetOrPut(cacheKey: String, ttlMs: Long, compute: suspend () -> V?): V? {
        // Check existing
        if (backend.exists(cacheKey)) {
            val existing = valueLock.withLock { valueStore[cacheKey] }
            if (existing != null) {
                recordHit()
                return existing
            }
        }

        // Compute new value
        val computed = compute()
        if (computed != null) {
            backend.set(cacheKey, MARKER, ttlMs)
            valueLock.withLock { valueStore[cacheKey] = computed }
        }
        recordMiss()
        return computed
    }

    // ========== APP-scoped operations ==========

    override suspend fun getApp(key: K): V? = internalGet(appKey(key))

    override suspend fun putApp(key: K, value: V): V? =
        internalPut(appKey(key), value, ttlConfig.app.inWholeMilliseconds)

    override suspend fun removeApp(key: K): V? = internalRemove(appKey(key))

    override suspend fun getOrPutApp(key: K, compute: suspend () -> V?): V? =
        internalGetOrPut(appKey(key), ttlConfig.app.inWholeMilliseconds, compute)

    // ========== TENANT-scoped operations ==========

    override suspend fun getTenant(tenantId: String, key: K): V? =
        internalGet(tenantKey(tenantId, key))

    override suspend fun putTenant(tenantId: String, key: K, value: V): V? =
        internalPut(tenantKey(tenantId, key), value, ttlConfig.tenant.inWholeMilliseconds)

    override suspend fun removeTenant(tenantId: String, key: K): V? =
        internalRemove(tenantKey(tenantId, key))

    override suspend fun getOrPutTenant(tenantId: String, key: K, compute: suspend () -> V?): V? =
        internalGetOrPut(tenantKey(tenantId, key), ttlConfig.tenant.inWholeMilliseconds, compute)

    // ========== PRINCIPAL-scoped operations ==========

    override suspend fun getPrincipal(principalId: String, key: K): V? =
        internalGet(principalKey(principalId, key))

    override suspend fun putPrincipal(principalId: String, key: K, value: V): V? =
        internalPut(principalKey(principalId, key), value, ttlConfig.principal.inWholeMilliseconds)

    override suspend fun removePrincipal(principalId: String, key: K): V? =
        internalRemove(principalKey(principalId, key))

    override suspend fun getOrPutPrincipal(principalId: String, key: K, compute: suspend () -> V?): V? =
        internalGetOrPut(principalKey(principalId, key), ttlConfig.principal.inWholeMilliseconds, compute)

    // ========== Maintenance operations ==========

    override suspend fun clear() {
        val pattern = "$namespace::*"
        evictions += backend.deleteByPattern(pattern)
        valueLock.withLock { valueStore.clear() }
    }

    override suspend fun clearApp() {
        val pattern = "$namespace::APP::*"
        evictions += backend.deleteByPattern(pattern)
        valueLock.withLock {
            val keysToRemove = valueStore.keys.filter { it.startsWith("$namespace::APP::") }
            keysToRemove.forEach { valueStore.remove(it) }
        }
    }

    override suspend fun clearTenant(tenantId: String) {
        val pattern = "$namespace::TENANT::$tenantId::*"
        evictions += backend.deleteByPattern(pattern)
        valueLock.withLock {
            val prefix = "$namespace::TENANT::$tenantId::"
            val keysToRemove = valueStore.keys.filter { it.startsWith(prefix) }
            keysToRemove.forEach { valueStore.remove(it) }
        }
    }

    override suspend fun clearPrincipal(principalId: String) {
        val pattern = "$namespace::PRINCIPAL::$principalId::*"
        evictions += backend.deleteByPattern(pattern)
        valueLock.withLock {
            val prefix = "$namespace::PRINCIPAL::$principalId::"
            val keysToRemove = valueStore.keys.filter { it.startsWith(prefix) }
            keysToRemove.forEach { valueStore.remove(it) }
        }
    }

    override suspend fun evictExpired() {
        // Clean up valueStore entries whose backend keys have expired
        val allKeys = valueLock.withLock { valueStore.keys.toList() }
        val keysToRemove = allKeys.filter { !backend.exists(it) }
        if (keysToRemove.isNotEmpty()) {
            valueLock.withLock {
                keysToRemove.forEach { valueStore.remove(it) }
            }
            evictions += keysToRemove.size
        }
    }

    override suspend fun getStatistics(): CacheStatistics {
        // Count entries per scope from the valueStore
        val appPrefix = "$namespace::APP::"
        val tenantPrefix = "$namespace::TENANT::"
        val principalPrefix = "$namespace::PRINCIPAL::"

        val totalSize = valueLock.withLock {
            valueStore.keys.count { key ->
                key.startsWith(appPrefix) || key.startsWith(tenantPrefix) || key.startsWith(principalPrefix)
            }.toLong()
        }

        return statsLock.withLock {
            CacheStatistics(
                namespace = namespace,
                hits = hits,
                misses = misses,
                size = totalSize,
                maxSize = backend.size(),
                evictions = evictions
            )
        }
    }

    override suspend fun close() {
        // Clear all entries
        clear()
        // Close the backend
        backend.close()
    }
}
