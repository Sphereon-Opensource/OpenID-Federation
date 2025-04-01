package com.sphereon.oid.fed.cache

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Data class representing configuration settings for a cache implementation.
 *
 * @property maxSize Defines the maximum number of entries that the cache can hold. Defaults to 1000.
 * @property expireAfterWrite Specifies the duration after which entries are automatically invalidated once written. Defaults to 1800 seconds.
 * @property expireAfterAccess Specifies the duration after which entries are invalidated if not accessed. This is optional and defaults to null, meaning it is not used.
 * @property persistentCacheEnabled Determines if a persistent cache feature is enabled. Defaults to false.
 * @property persistentCachePath Specifies the file path for the persistent cache when enabled. Defaults to null.
 * @property evictOnClose When set to true, ensures the cache content is evicted upon closing. Defaults to false.
 * @property cleanupOnStart Indicates whether to perform a cleanup operation when the cache is initialized. Defaults to true.
 * @property compressionEnabled Indicates if data compression is enabled for cache entries. Defaults to false.
 * @property compressionThresholdBytes Determines the minimum size (in bytes) for entries to be considered for compression. Defaults to 1024 bytes.
 */
data class CacheConfig(
    val maxSize: Long = 1000,
    val expireAfterWrite: Duration = 1800.seconds,
    val expireAfterAccess: Duration? = null,
    val persistentCacheEnabled: Boolean = false,
    val persistentCachePath: String? = null,
    val evictOnClose: Boolean = false,
    val cleanupOnStart: Boolean = true,
    val compressionEnabled: Boolean = false,
    val compressionThresholdBytes: Long = 1024L
)