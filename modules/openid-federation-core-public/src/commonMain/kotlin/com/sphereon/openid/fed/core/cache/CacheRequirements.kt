package com.sphereon.openid.fed.core.cache

/**
 * Declarative specification of cache requirements for a namespace.
 *
 * This follows the IDK pattern of declaring cache needs upfront,
 * allowing the CacheManager to configure and optimize caches appropriately.
 *
 * @property namespace Unique identifier for this cache (e.g., "oidf.http-resolver")
 * @property locality Where cache data should be stored
 * @property distributedFallback Whether to fall back to distributed cache if local misses
 * @property writeThrough Whether writes should go to both local and distributed caches
 * @property ttlConfig TTL configuration for different scopes
 * @property maxLocalEntries Maximum entries in local cache (for LRU eviction)
 * @property persistent Whether cache should survive restarts (requires file-backed storage)
 * @property tags Optional tags for cache management and monitoring
 */
data class CacheRequirements(
    val namespace: String,
    val locality: CacheLocality = CacheLocality.LOCAL_PREFERRED,
    val distributedFallback: Boolean = false,
    val writeThrough: Boolean = false,
    val ttlConfig: CacheTtlConfig = CacheTtlConfig.DEFAULT,
    val maxLocalEntries: Long = 1000,
    val persistent: Boolean = false,
    val tags: Set<String> = emptySet()
) {
    init {
        require(namespace.isNotBlank()) { "Cache namespace cannot be blank" }
        require(maxLocalEntries > 0) { "maxLocalEntries must be positive" }
    }

    /**
     * Builder for creating CacheRequirements with a fluent API.
     */
    class Builder(private val namespace: String) {
        private var locality: CacheLocality = CacheLocality.LOCAL_PREFERRED
        private var distributedFallback: Boolean = false
        private var writeThrough: Boolean = false
        private var ttlConfig: CacheTtlConfig = CacheTtlConfig.DEFAULT
        private var maxLocalEntries: Long = 1000
        private var persistent: Boolean = false
        private var tags: MutableSet<String> = mutableSetOf()

        fun locality(locality: CacheLocality) = apply { this.locality = locality }
        fun distributedFallback(enabled: Boolean) = apply { this.distributedFallback = enabled }
        fun writeThrough(enabled: Boolean) = apply { this.writeThrough = enabled }
        fun ttlConfig(config: CacheTtlConfig) = apply { this.ttlConfig = config }
        fun maxLocalEntries(max: Long) = apply { this.maxLocalEntries = max }
        fun persistent(enabled: Boolean) = apply { this.persistent = enabled }
        fun tags(vararg tags: String) = apply { this.tags.addAll(tags) }

        fun build() = CacheRequirements(
            namespace = namespace,
            locality = locality,
            distributedFallback = distributedFallback,
            writeThrough = writeThrough,
            ttlConfig = ttlConfig,
            maxLocalEntries = maxLocalEntries,
            persistent = persistent,
            tags = tags.toSet()
        )
    }

    companion object {
        fun builder(namespace: String) = Builder(namespace)
    }
}
