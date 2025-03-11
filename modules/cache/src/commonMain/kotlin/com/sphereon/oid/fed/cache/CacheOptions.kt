package com.sphereon.oid.fed.cache

/**
 * Enum representation of various caching strategies used for data retrieval in cache implementations.
 */
enum class CacheStrategy {
    /**
     * Cache strategy that prioritizes fetching data from the cache before attempting remote retrieval.
     *
     * When using this strategy, the cache is checked first. If a valid cached value is found, it is returned.
     * If no cached value is available, the system may fallback to other methods like remote retrieval.
     *
     * This strategy is useful for minimizing network calls and improving response time when cached data is available.
     */
    CACHE_FIRST,
    /**
     * Represents a cache handling strategy where data is only fetched from the cache
     * and no attempt is made to retrieve data from any remote or external source.
     *
     * Using this strategy assumes that the cache contains all required data,
     * and if the data is not found in the cache, it will not attempt to create
     * or fetch the value through other means.
     */
    CACHE_ONLY,
    /**
     * Represents a cache strategy where data is always fetched from the remote source,
     * regardless of whether it is present or valid in the cache.
     *
     * This strategy bypasses the cache completely and forces retrieval from the remote
     * backend or data source. It is typically used in scenarios where the most up-to-date
     * information is required and cached data could be stale or invalid.
     *
     * Applicable in implementations like `InMemoryCache` to enforce retrieval strategies
     * for data storage and access.
     */
    FORCE_REMOTE
}

/**
 * Data class representing options for configuring cache behavior.
 *
 * @property strategy Defines the strategy for data retrieval from the cache. It determines
 * how the cache interacts with the underlying data source based on the predefined strategies
 * provided by the `CacheStrategy` enumeration. The default value is `CacheStrategy.CACHE_FIRST`.
 */
data class CacheOptions(
    val strategy: CacheStrategy = CacheStrategy.CACHE_FIRST
)