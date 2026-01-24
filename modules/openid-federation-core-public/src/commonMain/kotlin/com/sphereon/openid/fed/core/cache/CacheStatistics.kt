package com.sphereon.openid.fed.core.cache

/**
 * Statistics for a cache namespace.
 *
 * Provides insights into cache performance and utilization for monitoring.
 */
data class CacheStatistics(
    /**
     * Cache namespace these statistics are for.
     */
    val namespace: String,

    /**
     * Total number of cache hits (successful lookups).
     */
    val hits: Long = 0,

    /**
     * Total number of cache misses (failed lookups).
     */
    val misses: Long = 0,

    /**
     * Current number of entries in the cache.
     */
    val size: Long = 0,

    /**
     * Maximum configured size of the cache.
     */
    val maxSize: Long = 0,

    /**
     * Number of entries that were evicted.
     */
    val evictions: Long = 0,

    /**
     * Number of entries that expired.
     */
    val expirations: Long = 0
) {
    /**
     * Total number of lookups (hits + misses).
     */
    val totalLookups: Long get() = hits + misses

    /**
     * Hit rate as a ratio (0.0 to 1.0).
     * Returns 0.0 if no lookups have been made.
     */
    val hitRate: Double get() = if (totalLookups > 0) hits.toDouble() / totalLookups else 0.0

    /**
     * Miss rate as a ratio (0.0 to 1.0).
     * Returns 0.0 if no lookups have been made.
     */
    val missRate: Double get() = if (totalLookups > 0) misses.toDouble() / totalLookups else 0.0

    /**
     * Cache utilization as a ratio (0.0 to 1.0).
     * Returns 0.0 if maxSize is 0.
     */
    val utilization: Double get() = if (maxSize > 0) size.toDouble() / maxSize else 0.0

    companion object {
        /**
         * Empty statistics for a namespace.
         */
        fun empty(namespace: String) = CacheStatistics(namespace = namespace)
    }
}
