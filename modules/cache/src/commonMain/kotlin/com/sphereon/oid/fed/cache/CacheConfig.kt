package com.sphereon.oid.fed.cache

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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