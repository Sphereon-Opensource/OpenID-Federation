package com.sphereon.oid.fed.cache

import com.mayakapps.kache.InMemoryKache
import com.mayakapps.kache.KacheStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlin.time.Duration

class InMemoryCache<K : Any, V : Any>(
    private val maxSize: Long = 1000,
    private val expireAfterWrite: Duration? = null,
    private val expireAfterAccess: Duration? = null,
    private val strategy: KacheStrategy = KacheStrategy.LRU,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : Cache<K, V> {

    private val cache: InMemoryKache<K, V> = InMemoryKache(maxSize) {
        creationScope = scope
        strategy = this@InMemoryCache.strategy
        expireAfterWrite?.let { expireAfterWriteDuration = it }
        expireAfterAccess?.let { expireAfterAccessDuration = it }
        maxSize = this@InMemoryCache.maxSize
    }

    override suspend fun clear() {
        cache.clear()
    }

    override suspend fun close() {
        // InMemoryKache doesn't require explicit closing
    }

    override suspend fun evictAll() {
        cache.evictAll()
    }

    override suspend fun evictExpired() {
        cache.evictExpired()
    }

    override suspend fun trimToSize(size: Long) {
        cache.trimToSize(size)
    }

    override suspend fun get(key: K, options: CacheOptions): V? {
        // Check cache first unless FORCE_REMOTE
        if (options.strategy != CacheStrategy.FORCE_REMOTE) {
            val cachedValue = cache.get(key)
            if (cachedValue != null) {
                return cachedValue
            }

            // If cache-only, return null
            if (options.strategy == CacheStrategy.CACHE_ONLY) {
                return null
            }
        }

        // At this point we either have FORCE_REMOTE or CACHE_FIRST with no valid cache entry
        return null // The actual value should be created using getOrPut with a creationFunction
    }

    override suspend fun getAllKeys(options: CacheOptions): Set<K> {
        val kacheKeys = cache.getAllKeys()
        return (kacheKeys.keys + kacheKeys.underCreationKeys).toSet()
    }

    override fun getIfAvailable(key: K, options: CacheOptions): V? {
        return cache.getIfAvailable(key)
    }

    override fun getIfAvailableOrDefault(key: K, defaultValue: V, options: CacheOptions): V {
        return getIfAvailable(key, options) ?: defaultValue
    }

    override suspend fun getKeys(options: CacheOptions): Set<K> {
        return cache.getKeys().toSet()
    }

    override suspend fun getOrDefault(key: K, defaultValue: V, options: CacheOptions): V {
        return get(key, options) ?: defaultValue
    }

    override suspend fun getOrPut(
        key: K,
        creationFunction: suspend (key: K) -> V?,
        options: CacheOptions
    ): V? {
        return cache.getOrPut(key, creationFunction)
    }

    /*override suspend fun getUnderCreationKeys(options: CacheOptions): Set<K> {
        return cache.getUnderCreationKeys()
    }*/

    override suspend fun put(key: K, value: V): V? {
        return cache.put(key, value)
    }

    override suspend fun put(
        key: K,
        creationFunction: suspend (key: K) -> V?
    ): V? {
        return cache.put(key, creationFunction)
    }

    override suspend fun putAll(from: Map<out K, V>) {
        cache.putAll(from)
    }

   /* override suspend fun putAsync(
        key: K,
        creationFunction: suspend (key: K) -> V?
    ): Deferred<V?> {
        return cache.putAsync(key, creationFunction)
    }*/

    override suspend fun remove(key: K): V? {
        return cache.remove(key)
    }

    /*override suspend fun removeAllUnderCreation() {
        cache.getUnderCreationKeys().forEach { cache.remove(it) }
    }*/

    override suspend fun resize(maxSize: Long) {
        trimToSize(maxSize)
    }

    override suspend fun getCurrentSize(options: CacheOptions): Long {
        return cache.getKeys().size.toLong()
    }

    override suspend fun getMaxSize(options: CacheOptions): Long {
        return cache.maxSize
    }
}