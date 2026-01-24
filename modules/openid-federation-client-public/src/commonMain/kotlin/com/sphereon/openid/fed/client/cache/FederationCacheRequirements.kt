package com.sphereon.openid.fed.client.cache

import com.sphereon.openid.fed.core.cache.CacheLocality
import com.sphereon.openid.fed.core.cache.CacheRequirements
import com.sphereon.openid.fed.core.cache.CacheTtlConfig
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Federation-specific cache requirements definitions.
 *
 * These requirements define the caching behavior for different federation subsystems,
 * following IDK's declarative cache configuration pattern.
 *
 * Cache namespaces:
 * - HTTP_RESOLVER: HTTP response caching with ETag/Last-Modified support
 * - ENTITY_CONFIG: Entity configuration statement caching
 * - TRUST_CHAIN: Trust chain resolution caching
 * - TRUST_MARK: Trust mark verification caching
 *
 * Scope usage:
 * - APP scope: Trust anchor configs (shared, stable, longer TTL)
 * - TENANT scope: Per-account entity configs (isolated per account)
 */
object FederationCacheRequirements {

    /**
     * HTTP response caching (entity configurations, subordinate statements).
     *
     * - APP scope: Trust anchor configs (shared across all accounts)
     * - TENANT scope: Per-account entity configs (isolated per account)
     *
     * Features:
     * - Supports HTTP conditional requests (ETag, Last-Modified)
     * - LRU eviction with configurable max entries
     * - Local-preferred with optional distributed fallback
     */
    val HTTP_RESOLVER = CacheRequirements(
        namespace = "oidf.http-resolver",
        locality = CacheLocality.LOCAL_PREFERRED,
        distributedFallback = true,
        writeThrough = false,
        ttlConfig = CacheTtlConfig(
            app = 30.minutes,    // Trust anchor configs (stable)
            tenant = 15.minutes  // Per-account entity configs
        ),
        maxLocalEntries = 1000,
        persistent = false,
        tags = setOf("http", "federation")
    )

    /**
     * Entity configuration statement caching.
     *
     * - APP scope: Well-known trust anchors (long TTL, shared)
     * - TENANT scope: Per-account federation entity configs
     *
     * Caches parsed EntityConfigurationStatement objects after JWT validation.
     */
    val ENTITY_CONFIG = CacheRequirements(
        namespace = "oidf.entity-config",
        locality = CacheLocality.LOCAL_PREFERRED,
        distributedFallback = true,
        writeThrough = false,
        ttlConfig = CacheTtlConfig(
            app = 1.hours,        // Well-known trust anchors
            tenant = 30.minutes   // Per-account configs
        ),
        maxLocalEntries = 500,
        persistent = false,
        tags = setOf("entity", "federation")
    )

    /**
     * Trust chain resolution caching.
     *
     * - APP scope: Shared trust chains (e.g., to well-known anchors)
     * - TENANT scope: Per-account trust chain resolutions
     *
     * Caches resolved trust chains to avoid redundant chain building.
     */
    val TRUST_CHAIN = CacheRequirements(
        namespace = "oidf.trust-chain",
        locality = CacheLocality.LOCAL_PREFERRED,
        distributedFallback = false, // Trust chains are request-specific
        writeThrough = false,
        ttlConfig = CacheTtlConfig(
            app = 15.minutes,    // Shared resolved chains
            tenant = 10.minutes  // Per-account chains
        ),
        maxLocalEntries = 200,
        persistent = false,
        tags = setOf("trust-chain", "federation")
    )

    /**
     * Trust mark verification caching.
     *
     * - APP scope: Verified trust marks for well-known issuers
     * - TENANT scope: Per-account trust mark verifications
     *
     * Caches trust mark validation results to avoid redundant verification.
     */
    val TRUST_MARK = CacheRequirements(
        namespace = "oidf.trust-mark",
        locality = CacheLocality.LOCAL_PREFERRED,
        distributedFallback = false,
        writeThrough = false,
        ttlConfig = CacheTtlConfig(
            app = 10.minutes,
            tenant = 5.minutes
        ),
        maxLocalEntries = 500,
        persistent = false,
        tags = setOf("trust-mark", "federation")
    )

    /**
     * Get all federation cache requirements.
     */
    val ALL = listOf(HTTP_RESOLVER, ENTITY_CONFIG, TRUST_CHAIN, TRUST_MARK)

    /**
     * Get all federation cache namespaces.
     */
    val NAMESPACES = ALL.map { it.namespace }.toSet()
}
