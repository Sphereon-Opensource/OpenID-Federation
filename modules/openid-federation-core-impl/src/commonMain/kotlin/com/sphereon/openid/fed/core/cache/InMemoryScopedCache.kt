package com.sphereon.openid.fed.core.cache

import com.mayakapps.kache.InMemoryKache
import com.mayakapps.kache.KacheStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * In-memory implementation of ScopedCache using Kache as the underlying storage.
 *
 * This implementation maintains separate Kache instances for each scope:
 * - One cache for APP scope
 * - One cache per tenant (lazily created)
 * - One cache per principal (lazily created)
 *
 * ## Lifecycle Management
 *
 * This cache manages an internal CoroutineScope for background operations.
 * **You must call [close] when you're done using the cache** to release resources
 * and prevent memory leaks.
 *
 * @param K The type of cache keys
 * @param V The type of cached values
 */
class InMemoryScopedCache<K : Any, V : Any>(
    override val namespace: String,
    private val maxSize: Long = 1000,
    private val ttlConfig: CacheTtlConfig = CacheTtlConfig.DEFAULT,
    parentScope: CoroutineScope? = null
) : ScopedCache<K, V> {

    // Create a managed scope with SupervisorJob for lifecycle control
    private val job: Job = SupervisorJob(parentScope?.coroutineContext?.get(Job))
    private val scope: CoroutineScope = parentScope ?: CoroutineScope(job + Dispatchers.Default)

    // Statistics counters
    private var hits: Long = 0
    private var misses: Long = 0
    private var evictions: Long = 0
    private val statsLock = Mutex()

    // APP scope cache
    private val appCache: InMemoryKache<K, V> = createKache(ttlConfig.app)

    // TENANT scope caches (keyed by tenant ID)
    private val tenantCaches = mutableMapOf<String, InMemoryKache<ScopedKey<K>, V>>()
    private val tenantCachesLock = Mutex()

    // PRINCIPAL scope caches (keyed by principal ID)
    private val principalCaches = mutableMapOf<String, InMemoryKache<ScopedKey<K>, V>>()
    private val principalCachesLock = Mutex()

    private fun createKache(ttl: Duration): InMemoryKache<K, V> = InMemoryKache(maxSize) {
        creationScope = scope
        strategy = KacheStrategy.LRU
        expireAfterWriteDuration = ttl
        maxSize = this@InMemoryScopedCache.maxSize
    }

    private fun createScopedKache(ttl: Duration): InMemoryKache<ScopedKey<K>, V> = InMemoryKache(maxSize) {
        creationScope = scope
        strategy = KacheStrategy.LRU
        expireAfterWriteDuration = ttl
        maxSize = this@InMemoryScopedCache.maxSize
    }

    private suspend fun getOrCreateTenantCache(tenantId: String): InMemoryKache<ScopedKey<K>, V> {
        return tenantCachesLock.withLock {
            tenantCaches.getOrPut(tenantId) {
                createScopedKache(ttlConfig.tenant)
            }
        }
    }

    private suspend fun getOrCreatePrincipalCache(principalId: String): InMemoryKache<ScopedKey<K>, V> {
        return principalCachesLock.withLock {
            principalCaches.getOrPut(principalId) {
                createScopedKache(ttlConfig.principal)
            }
        }
    }

    private suspend fun recordHit() = statsLock.withLock { hits++ }
    private suspend fun recordMiss() = statsLock.withLock { misses++ }

    // ========== APP-scoped operations ==========

    override suspend fun getApp(key: K): V? {
        val result = appCache.get(key)
        if (result != null) recordHit() else recordMiss()
        return result
    }

    override suspend fun putApp(key: K, value: V): V? {
        return appCache.put(key, value)
    }

    override suspend fun removeApp(key: K): V? {
        return appCache.remove(key)
    }

    override suspend fun getOrPutApp(key: K, compute: suspend () -> V?): V? {
        val existing = appCache.getIfAvailable(key)
        if (existing != null) {
            recordHit()
            return existing
        }

        val computed = compute()
        if (computed != null) {
            appCache.put(key, computed)
        }
        recordMiss()
        return computed
    }

    // ========== TENANT-scoped operations ==========

    override suspend fun getTenant(tenantId: String, key: K): V? {
        val cache = getOrCreateTenantCache(tenantId)
        val result = cache.get(ScopedKey(tenantId, key))
        if (result != null) recordHit() else recordMiss()
        return result
    }

    override suspend fun putTenant(tenantId: String, key: K, value: V): V? {
        val cache = getOrCreateTenantCache(tenantId)
        return cache.put(ScopedKey(tenantId, key), value)
    }

    override suspend fun removeTenant(tenantId: String, key: K): V? {
        val cache = getOrCreateTenantCache(tenantId)
        return cache.remove(ScopedKey(tenantId, key))
    }

    override suspend fun getOrPutTenant(tenantId: String, key: K, compute: suspend () -> V?): V? {
        val cache = getOrCreateTenantCache(tenantId)
        val scopedKey = ScopedKey(tenantId, key)

        val existing = cache.getIfAvailable(scopedKey)
        if (existing != null) {
            recordHit()
            return existing
        }

        val computed = compute()
        if (computed != null) {
            cache.put(scopedKey, computed)
        }
        recordMiss()
        return computed
    }

    // ========== PRINCIPAL-scoped operations ==========

    override suspend fun getPrincipal(principalId: String, key: K): V? {
        val cache = getOrCreatePrincipalCache(principalId)
        val result = cache.get(ScopedKey(principalId, key))
        if (result != null) recordHit() else recordMiss()
        return result
    }

    override suspend fun putPrincipal(principalId: String, key: K, value: V): V? {
        val cache = getOrCreatePrincipalCache(principalId)
        return cache.put(ScopedKey(principalId, key), value)
    }

    override suspend fun removePrincipal(principalId: String, key: K): V? {
        val cache = getOrCreatePrincipalCache(principalId)
        return cache.remove(ScopedKey(principalId, key))
    }

    override suspend fun getOrPutPrincipal(principalId: String, key: K, compute: suspend () -> V?): V? {
        val cache = getOrCreatePrincipalCache(principalId)
        val scopedKey = ScopedKey(principalId, key)

        val existing = cache.getIfAvailable(scopedKey)
        if (existing != null) {
            recordHit()
            return existing
        }

        val computed = compute()
        if (computed != null) {
            cache.put(scopedKey, computed)
        }
        recordMiss()
        return computed
    }

    // ========== Maintenance operations ==========

    override suspend fun clear() {
        clearApp()
        tenantCachesLock.withLock {
            tenantCaches.values.forEach { it.clear() }
            tenantCaches.clear()
        }
        principalCachesLock.withLock {
            principalCaches.values.forEach { it.clear() }
            principalCaches.clear()
        }
    }

    override suspend fun clearApp() {
        appCache.clear()
    }

    override suspend fun clearTenant(tenantId: String) {
        tenantCachesLock.withLock {
            tenantCaches[tenantId]?.clear()
            tenantCaches.remove(tenantId)
        }
    }

    override suspend fun clearPrincipal(principalId: String) {
        principalCachesLock.withLock {
            principalCaches[principalId]?.clear()
            principalCaches.remove(principalId)
        }
    }

    override suspend fun evictExpired() {
        appCache.evictExpired()
        tenantCachesLock.withLock {
            tenantCaches.values.forEach { it.evictExpired() }
        }
        principalCachesLock.withLock {
            principalCaches.values.forEach { it.evictExpired() }
        }
    }

    override suspend fun getStatistics(): CacheStatistics {
        val appSize = appCache.getKeys().size.toLong()
        val tenantSize = tenantCachesLock.withLock {
            tenantCaches.values.sumOf { it.getKeys().size.toLong() }
        }
        val principalSize = principalCachesLock.withLock {
            principalCaches.values.sumOf { it.getKeys().size.toLong() }
        }
        val totalSize = appSize + tenantSize + principalSize

        return statsLock.withLock {
            CacheStatistics(
                namespace = namespace,
                hits = hits,
                misses = misses,
                size = totalSize,
                maxSize = maxSize * 3, // Rough estimate (app + avg tenant + avg principal)
                evictions = evictions
            )
        }
    }

    override suspend fun close() {
        // Clear all caches first
        clear()
        // Cancel the scope to stop any background operations
        job.cancel("InMemoryScopedCache closed: $namespace")
    }
}

/**
 * A key that includes scope identifier for tenant/principal isolation.
 */
internal data class ScopedKey<K>(
    val scopeId: String,
    val key: K
)
