package com.sphereon.oid.fed.cache

/**
 * Represents a generic cache interface for handling key-value pairs with additional functionalities such as size
 * management, expiration handling, and data retrieval strategies.
 *
 * @param K The type of the keys maintained by this cache.
 * @param V The type of mapped values in this cache.
 */
interface Cache<K, V> {
    suspend fun clear()
    suspend fun close()

    suspend fun evictAll()
    suspend fun evictExpired()
    suspend fun trimToSize(size: Long)

    suspend fun get(key: K, options: CacheOptions = CacheOptions()): V?
    suspend fun getAllKeys(options: CacheOptions = CacheOptions()): Set<K>
    fun getIfAvailable(key: K, options: CacheOptions = CacheOptions()): V?
    fun getIfAvailableOrDefault(key: K, defaultValue: V, options: CacheOptions = CacheOptions()): V
    suspend fun getKeys(options: CacheOptions = CacheOptions()): Set<K>
    suspend fun getOrDefault(key: K, defaultValue: V, options: CacheOptions = CacheOptions()): V
    suspend fun getOrPut(
        key: K,
        creationFunction: suspend (key: K) -> V?,
        options: CacheOptions = CacheOptions()
    ): V?

//    suspend fun getUnderCreationKeys(options: CacheOptions = CacheOptions()): Set<K>

    suspend fun put(key: K, value: V): V?
    suspend fun put(
        key: K,
        creationFunction: suspend (key: K) -> V?
    ): V?

    suspend fun putAll(from: Map<out K, V>)
//    suspend fun putAsync(
//        key: K,
//        creationFunction: suspend (key: K) -> V?
//    ): Deferred<V?>

    suspend fun remove(key: K): V?
//    suspend fun removeAllUnderCreation()

    suspend fun resize(maxSize: Long)
    suspend fun getCurrentSize(options: CacheOptions = CacheOptions()): Long
    suspend fun getMaxSize(options: CacheOptions = CacheOptions()): Long
}