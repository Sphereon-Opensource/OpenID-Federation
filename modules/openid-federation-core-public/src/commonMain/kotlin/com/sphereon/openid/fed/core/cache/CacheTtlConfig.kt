package com.sphereon.openid.fed.core.cache

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Configuration for cache entry time-to-live (TTL) at different scope levels.
 *
 * Different scopes may have different TTL requirements:
 * - APP scope: Typically longer TTL for stable shared data
 * - TENANT scope: Moderate TTL for per-account data
 * - PRINCIPAL scope: Short TTL for request-specific data
 */
data class CacheTtlConfig(
    /**
     * TTL for APP-scoped entries.
     * Default: 30 minutes (suitable for trust anchor configurations).
     */
    val app: Duration = 30.minutes,

    /**
     * TTL for TENANT-scoped entries.
     * Default: 15 minutes (suitable for per-account entity configs).
     */
    val tenant: Duration = 15.minutes,

    /**
     * TTL for PRINCIPAL-scoped entries.
     * Default: 5 minutes (suitable for request-specific caching).
     */
    val principal: Duration = 5.minutes
) {
    /**
     * Get the TTL for the specified scope.
     */
    fun forScope(scope: CacheScope): Duration = when (scope) {
        CacheScope.APP -> app
        CacheScope.TENANT -> tenant
        CacheScope.PRINCIPAL -> principal
    }

    companion object {
        /**
         * Default TTL configuration.
         */
        val DEFAULT = CacheTtlConfig()

        /**
         * Short TTL configuration for frequently changing data.
         */
        val SHORT = CacheTtlConfig(
            app = 10.minutes,
            tenant = 5.minutes,
            principal = 2.minutes
        )

        /**
         * Long TTL configuration for stable data.
         */
        val LONG = CacheTtlConfig(
            app = 60.minutes,
            tenant = 30.minutes,
            principal = 15.minutes
        )
    }
}
