package com.sphereon.openid.fed.client

import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.ktor.http.client.provider.HttpClientFactory
import com.sphereon.ktor.http.client.provider.HttpClientOptions
import com.sphereon.openid.fed.client.cache.FederationCacheRequirements
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.core.cache.CacheManager
import com.sphereon.openid.fed.httpResolver.HttpMetadata
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
     * @param cacheManager Cache manager for creating scoped caches
     * @return Configured FederationContext instance
     */
    @Provides
    @SingleIn(SessionScope::class)
    fun provideFederationContext(
        jwtService: JwtService,
        httpClientFactory: HttpClientFactory,
        cacheManager: CacheManager
    ): FederationContext {
        // Create HTTP client
        val httpClient = httpClientFactory.createClient(HttpClientOptions.createDefault())

        // Create scoped caches from requirements
        val httpCache = cacheManager.getOrCreate<String, HttpMetadata<String>>(
            FederationCacheRequirements.HTTP_RESOLVER
        )
        val trustChainCache = cacheManager.getOrCreate<String, String>(
            FederationCacheRequirements.TRUST_CHAIN
        )

        // Create HTTP resolver with scoped cache
        val httpResolver = HttpResolver(
            httpClient = httpClient,
            cache = httpCache,
            responseMapper = { response -> response.bodyAsText() }
        )

        // Create federation context with all dependencies
        return FederationContext(
            jwtService = jwtService,
            httpResolver = httpResolver,
            cacheManager = cacheManager,
            trustChainCache = trustChainCache
        )
    }
}
