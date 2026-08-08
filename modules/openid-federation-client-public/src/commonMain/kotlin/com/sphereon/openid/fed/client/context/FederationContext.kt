package com.sphereon.openid.fed.client.context

import com.sphereon.core.api.cache.CacheManager
import com.sphereon.core.api.cache.ScopedCache
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.openid.fed.client.helpers.OfflineTrustChainPolicy
import com.sphereon.openid.fed.httpResolver.HttpResolver
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.log.LogService
import com.sphereon.openid.fed.openapi.models.Jwk
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
 * @param cacheManager IDK cache manager (optional, for statistics / extra namespaces)
 * @param trustChainCache IDK scoped cache for trust chain deduplication (optional)
 * @param trustAnchorPublicKeys Optional out-of-band Trust Anchor public keys, keyed by
 *   Trust Anchor Entity Identifier. When present for a TA, trust chain verification uses
 *   these keys as the root of trust (OIDFed 1.1 §4) instead of relying only on a
 *   self-consistent TA Entity Configuration.
 * @param understoodCriticalClaims Entity Statement `crit` extension claim names this
 *   deployment understands and can process (OIDFed 1.1 §3.2 step 13 / §13.4). Default empty
 *   → any non-standard `crit` entry fails closed during Trust Chain verification.
 * @param offlineTrustChainPolicy Extra freshness constraints for offline Trust Chains
 *   (`trust_chain` header / [OfflineTrustChainPolicy]). Default [OfflineTrustChainPolicy.DISABLED]
 *   (only statement `exp`/`iat` from chain verify). Deployments set max age / min remaining.
 * @param json JSON serializer (optional, defaults to lenient configuration)
 * @param logger Log service (optional, defaults to federation client logger)
 */
class FederationContext(
    val jwtService: JwtService,
    val httpResolver: HttpResolver<String>,
    val cacheManager: CacheManager? = null,
    val trustChainCache: ScopedCache<String, String>? = null,
    val trustAnchorPublicKeys: Map<String, List<Jwk>> = emptyMap(),
    val understoodCriticalClaims: Set<String> = emptySet(),
    val offlineTrustChainPolicy: OfflineTrustChainPolicy = OfflineTrustChainPolicy.DISABLED,
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    },
    val logger: LogService = Log.app().withTag("sphereon:oidf:client:context")
)
