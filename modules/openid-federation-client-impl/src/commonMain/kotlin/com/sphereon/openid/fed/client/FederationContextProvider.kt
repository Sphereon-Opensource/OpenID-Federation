package com.sphereon.openid.fed.client

import com.sphereon.core.api.cache.CacheManager
import com.sphereon.core.api.conf.TenantConfigService
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.ktor.http.client.provider.HttpClientFactory
import com.sphereon.ktor.http.client.provider.HttpClientOptions
import com.sphereon.openid.fed.client.cache.FederationCacheRequirements
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.core.cache.OidfCache
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.config.OidfConfigKeys
import com.sphereon.openid.fed.httpResolver.HttpMetadataCacheSerializers
import com.sphereon.openid.fed.httpResolver.HttpResolver
import io.ktor.client.statement.bodyAsText
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.SingleIn

/**
 * Provides FederationContext for dependency injection.
 *
 * This component contributes bindings that make FederationContext available
 * in the session scope, wiring up all required dependencies from IDK.
 */
@ContributesTo(SessionScope::class)
interface FederationContextComponent {

    /**
     * Provides a FederationContext instance configured with all required dependencies.
     *
     * @param jwtService IDK's JwtService for JWT verification
     * @param httpClientFactory Factory for creating HTTP clients
     * @param cacheManager IDK cache manager for creating scoped caches
     * @return Configured FederationContext instance
     */
    @Provides
    @SingleIn(SessionScope::class)
    fun provideFederationContext(
        jwtService: JwtService,
        httpClientFactory: HttpClientFactory,
        cacheManager: CacheManager,
        configBinder: OidfConfigBinder,
        execution: SessionExecution,
        tenantConfigService: TenantConfigService,
    ): FederationContext {
        val httpClient = httpClientFactory.createClient(HttpClientOptions.createDefault())
        val tenantId = execution.tenantId.takeUnless {
            it.isBlank() || it.equals("anonymous", ignoreCase = true)
        }

        val httpReqs = applyLocalityOverride(
            FederationCacheRequirements.HTTP_RESOLVER,
            configBinder,
            OidfConfigKeys.Cache.HTTP_RESOLVER_LOCALITY,
            tenantId,
            tenantConfigService,
        )
        val trustReqs = applyLocalityOverride(
            FederationCacheRequirements.TRUST_CHAIN,
            configBinder,
            OidfConfigKeys.Cache.TRUST_CHAIN_LOCALITY,
            tenantId,
            tenantConfigService,
        )

        // Portable serializers: safe for local + distributed IDK backends
        val httpCache = OidfCache.createStringKeyCache(
            manager = cacheManager,
            requirements = httpReqs,
            valueSerializer = HttpMetadataCacheSerializers.stringValue,
        )
        val trustChainCache = OidfCache.createStringKeyCache(
            manager = cacheManager,
            requirements = trustReqs,
            valueSerializer = com.sphereon.core.api.cache.CacheSerializers.string,
        )

        val httpResolver = HttpResolver(
            httpClient = httpClient,
            cache = httpCache,
            responseMapper = { response -> response.bodyAsText() }
        )

        return FederationContext(
            jwtService = jwtService,
            httpResolver = httpResolver,
            cacheManager = cacheManager,
            trustChainCache = trustChainCache
        )
    }
}

private fun applyLocalityOverride(
    base: com.sphereon.core.api.cache.CacheRequirements,
    configBinder: OidfConfigBinder,
    key: String,
    tenantId: String?,
    tenantConfigService: TenantConfigService,
): com.sphereon.core.api.cache.CacheRequirements {
    val raw = configBinder.getEffectiveCacheLocality(
        appKey = key,
        tenantId = tenantId,
        sessionTenantConfig = tenantConfigService,
    )
    if (raw.isEmpty()) return base
    val locality = FederationCacheRequirements.parseLocality(raw) ?: return base
    return FederationCacheRequirements.withLocality(base, locality)
}
