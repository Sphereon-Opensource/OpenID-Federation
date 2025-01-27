package com.sphereon.oid.fed.client.context

import com.sphereon.oid.fed.cache.Cache
import com.sphereon.oid.fed.client.crypto.cryptoService
import com.sphereon.oid.fed.client.fetch.fetchService
import com.sphereon.oid.fed.client.services.jwtService.JwtService
import com.sphereon.oid.fed.client.types.ICryptoService
import com.sphereon.oid.fed.client.types.IFetchService
import com.sphereon.oid.fed.httpResolver.HttpMetadata
import com.sphereon.oid.fed.httpResolver.HttpResolver
import com.sphereon.oid.fed.httpResolver.config.DefaultHttpResolverConfig
import com.sphereon.oid.fed.httpResolver.config.HttpResolverConfig
import com.sphereon.oid.fed.logger.Logger
import io.ktor.client.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.Json

class FederationContext private constructor(
    val fetchService: IFetchService,
    val cryptoService: ICryptoService,
    val json: Json,
    val logger: Logger = Logger.tag("sphereon:oidf:client:context"),
    val httpResolver: HttpResolver<String>
) {
    val jwtService: JwtService = JwtService(this)

    companion object {
        fun create(
            fetchService: IFetchService = fetchService(),
            cryptoService: ICryptoService = cryptoService(),
            httpClient: HttpClient,
            cache: Cache<String, HttpMetadata<String>>,
            httpResolverConfig: HttpResolverConfig = DefaultHttpResolverConfig(),
            json: Json = Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                isLenient = true
            }
        ): FederationContext {
            val resolver = HttpResolver(
                config = httpResolverConfig,
                httpClient = httpClient,
                cache = cache,
                responseMapper = { response -> response.bodyAsText() }
            )

            return FederationContext(
                fetchService = fetchService,
                cryptoService = cryptoService,
                json = json,
                httpResolver = resolver
            )
        }
    }
}