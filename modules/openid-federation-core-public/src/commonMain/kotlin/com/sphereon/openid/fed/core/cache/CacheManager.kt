package com.sphereon.openid.fed.core.cache

/**
 * Central cache orchestration service following IDK's CacheManager pattern.
 *
 * The CacheManager is responsible for:
 * - Creating and managing cache instances based on CacheRequirements
 * - Coordinating between local and distributed caches
 * - Providing aggregate statistics across all caches
 * - Lifecycle management of cache resources
 */
interface CacheManager {
    /**
     * Get or create a scoped cache for the given requirements.
     *
     * The cache is created based on the requirements if it doesn't exist.
     * Subsequent calls with the same namespace return the same cache instance.
     *
     * @param requirements The cache requirements specifying namespace, TTL, locality, etc.
     * @return A ScopedCache instance configured according to the requirements
     */
    fun <K : Any, V : Any> getOrCreate(requirements: CacheRequirements): ScopedCache<K, V>

    /**
     * Get an existing cache by namespace.
     *
     * @param namespace The cache namespace
     * @return The cache if it exists, null otherwise
     */
    fun <K : Any, V : Any> get(namespace: String): ScopedCache<K, V>?

    /**
     * Check if a cache exists for the given namespace.
     */
    fun exists(namespace: String): Boolean

    /**
     * Get statistics for a specific namespace.
     */
    suspend fun getStatistics(namespace: String): CacheStatistics?

    /**
     * Get aggregate statistics across all managed caches.
     *
     * @return Map of namespace to statistics
     */
    suspend fun aggregateStats(): Map<String, CacheStatistics>

    /**
     * Get all managed cache namespaces.
     */
    fun getNamespaces(): Set<String>

    /**
     * Clear a specific cache by namespace.
     */
    suspend fun clear(namespace: String)

    /**
     * Clear all managed caches.
     */
    suspend fun clearAll()

    /**
     * Evict expired entries from all caches.
     */
    suspend fun evictAllExpired()

    /**
     * Shutdown the cache manager and release all resources.
     */
    suspend fun shutdown()
}
