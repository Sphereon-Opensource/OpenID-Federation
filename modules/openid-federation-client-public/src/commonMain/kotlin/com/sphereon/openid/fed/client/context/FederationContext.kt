package com.sphereon.openid.fed.client.context

import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.openid.fed.core.cache.CacheManager
import com.sphereon.openid.fed.core.cache.ScopedCache
import com.sphereon.openid.fed.httpResolver.HttpResolver
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.log.LogService
import kotlinx.serialization.json.Json

/**
 * Context for federation client operations.
 *
 * Core dependencies (jwtService, httpResolver) must be provided via constructor.
 * The consumer's DI system is responsible for providing these dependencies.
 * Optional parameters have sensible defaults for convenience.
 *
 * @param jwtService IDK's JwtService for JWT verification (required)
 * @param httpResolver HTTP resolver for fetching federation data (required)
 * @param cacheManager Cache manager for creating and managing caches (optional, for statistics)
 * @param trustChainCache Scoped cache for trust chain deduplication (optional)
 * @param json JSON serializer (optional, defaults to lenient configuration)
 * @param logger Log service (optional, defaults to federation client logger)
 */
class FederationContext(
    val jwtService: JwtService,
    val httpResolver: HttpResolver<String>,
    val cacheManager: CacheManager? = null,
    val trustChainCache: ScopedCache<String, String>? = null,
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    },
    val logger: LogService = Log.app().withTag("sphereon:oidf:client:context")
)
