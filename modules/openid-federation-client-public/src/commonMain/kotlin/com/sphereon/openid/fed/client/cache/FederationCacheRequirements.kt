package com.sphereon.openid.fed.client.cache

import com.sphereon.core.api.cache.CacheLocality
import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.core.api.cache.CacheTtlConfig
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Federation-specific cache requirements (IDK [CacheRequirements]).
 *
 * Cache namespaces:
 * - HTTP_RESOLVER: HTTP response caching with ETag/Last-Modified support
 * - ENTITY_CONFIG: Entity configuration statement caching
 * - TRUST_CHAIN: Trust chain resolution caching
 * - TRUST_MARK: Trust mark verification caching
 *
 * Scope usage (IDK [com.sphereon.core.api.cache.CacheScope]):
 * - APP: Trust anchor configs (shared, stable, longer TTL)
 * - TENANT: Per-account entity configs (isolated per account)
 */
object FederationCacheRequirements {

    /**
     * HTTP response caching (entity configurations, subordinate statements).
     */
    val HTTP_RESOLVER = CacheRequirements(
        namespace = "oidf.http-resolver",
        locality = CacheLocality.LOCAL_PREFERRED,
        distributedFallback = true,
        writeThrough = false,
        ttlConfig = CacheTtlConfig(
            app = 30.minutes,
            tenant = 15.minutes,
        ),
        maxLocalEntries = 1000,
        persistent = false,
        tags = setOf("http", "federation"),
    )

    /**
     * Entity configuration statement caching.
     */
    val ENTITY_CONFIG = CacheRequirements(
        namespace = "oidf.entity-config",
        locality = CacheLocality.LOCAL_PREFERRED,
        distributedFallback = true,
        writeThrough = false,
        ttlConfig = CacheTtlConfig(
            app = 1.hours,
            tenant = 30.minutes,
        ),
        maxLocalEntries = 500,
        persistent = false,
        tags = setOf("entity", "federation"),
    )

    /**
     * Trust chain resolution caching.
     */
    val TRUST_CHAIN = CacheRequirements(
        namespace = "oidf.trust-chain",
        locality = CacheLocality.LOCAL_PREFERRED,
        distributedFallback = false,
        writeThrough = false,
        ttlConfig = CacheTtlConfig(
            app = 15.minutes,
            tenant = 10.minutes,
        ),
        maxLocalEntries = 200,
        persistent = false,
        tags = setOf("trust-chain", "federation"),
    )

    /**
     * Trust mark verification caching.
     */
    val TRUST_MARK = CacheRequirements(
        namespace = "oidf.trust-mark",
        locality = CacheLocality.LOCAL_PREFERRED,
        distributedFallback = false,
        writeThrough = false,
        ttlConfig = CacheTtlConfig(
            app = 10.minutes,
            tenant = 5.minutes,
        ),
        maxLocalEntries = 500,
        persistent = false,
        tags = setOf("trust-mark", "federation"),
    )

    /** All federation cache requirements. */
    val ALL = listOf(HTTP_RESOLVER, ENTITY_CONFIG, TRUST_CHAIN, TRUST_MARK)

    /** All federation cache namespaces. */
    val NAMESPACES = ALL.map { it.namespace }.toSet()

    /**
     * Copy [base] with an optional locality override from config/deployment.
     *
     * Example: multi-instance hosts with a distributed IDK backend can set
     * `oidf.cache.http.resolver.locality=DISTRIBUTED_PREFERRED`.
     */
    fun withLocality(base: CacheRequirements, locality: CacheLocality): CacheRequirements =
        base.copy(locality = locality)

    /**
     * Parse IDK [CacheLocality] from config string (case-insensitive enum name).
     */
    fun parseLocality(value: String?): CacheLocality? {
        if (value.isNullOrBlank()) return null
        return runCatching { CacheLocality.valueOf(value.trim().uppercase()) }.getOrNull()
    }
}
