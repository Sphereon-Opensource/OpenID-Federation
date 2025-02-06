package com.sphereon.oid.fed.client

import com.sphereon.oid.fed.cache.InMemoryCache
import com.sphereon.oid.fed.client.context.FederationContext
import com.sphereon.oid.fed.client.crypto.cryptoService
import com.sphereon.oid.fed.client.services.entityConfigurationStatementService.EntityConfigurationStatementService
import com.sphereon.oid.fed.client.services.trustChainService.TrustChainService
import com.sphereon.oid.fed.client.services.trustMarkService.TrustMarkService
import com.sphereon.oid.fed.client.types.*
import com.sphereon.oid.fed.httpResolver.config.DefaultHttpResolverConfig
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import io.ktor.client.*
import io.ktor.client.plugins.*
import kotlinx.serialization.json.Json
import kotlin.js.JsExport

/**
 * Federation client for reading and validating statements and trust chains.
 */
@JsExport.Ignore
class FederationClient(
    override val cryptoServiceCallback: ICryptoService? = null,
    override val httpClient: HttpClient? = null
) : IFederationClient {
    private val context = FederationContext.create(
        httpClient = httpClient ?: HttpClient() {
            install(HttpTimeout)
        },
        cryptoService = cryptoServiceCallback ?: cryptoService(),
        json = Json { ignoreUnknownKeys = true },
        cache = InMemoryCache(),
        httpResolverConfig = DefaultHttpResolverConfig()
    )
    private val trustChainService = TrustChainService(context)
    private val entityConfigurationService = EntityConfigurationStatementService(context)
    private val trustMarkService = TrustMarkService(context)

    /**
     * Builds a trust chain for the given entity identifier using the provided trust anchors.
     * It returns the first trust chain that is successfully resolved.
     *
     * @param entityIdentifier The entity identifier for which to build the trust chain.
     * @param trustAnchors The trust anchors to use for building the trust chain.
     * @param maxDepth The maximum depth to search for trust chain links.
     * @return A [TrustChainResolveResponse] object containing the resolved trust chain.
     */
    suspend fun trustChainResolve(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int = 5
    ): TrustChainResolveResponse {
        return trustChainService.resolve(entityIdentifier, trustAnchors, maxDepth)
    }

    /**
     * Verifies the trust chain.
     *
     * @param trustChain The trust chain to verify.
     * @param trustAnchor The trust anchor to use for verification. Optional.
     * @param currentTime The current time to use for verification. Defaults to the current epoch time in seconds.
     *
     * @return A [VerifyTrustChainResponse] object containing the verification result.
     */
    suspend fun trustChainVerify(
        trustChain: List<String>,
        trustAnchor: String?,
        currentTime: Long?
    ): VerifyTrustChainResponse {
        return trustChainService.verify(trustChain, trustAnchor, currentTime)
    }

    /**
     * Get an Entity Configuration Statement from an entity.
     *
     * @param entityIdentifier The entity identifier for which to get the statement.
     * @return EntityConfigurationStatement containing the entity configuration statement.
     */
    suspend fun entityConfigurationStatementGet(entityIdentifier: String): EntityConfigurationStatement {
        return entityConfigurationService.fetchEntityConfigurationStatement(entityIdentifier)
    }

    /**
     * Verifies a Trust Mark according to the OpenID Federation specification.
     *
     * @param trustMark The Trust Mark JWT string to validate
     * @param trustAnchorConfig The Trust Anchor's Entity Configuration
     * @param currentTime Optional timestamp for validation (defaults to current time)
     * @return TrustMarkValidationResponse containing the validation result and any error message
     */
    suspend fun trustMarksVerify(
        trustMark: String,
        trustAnchorConfig: EntityConfigurationStatement,
        currentTime: Long? = null
    ): TrustMarkValidationResponse {
        return trustMarkService.validateTrustMark(trustMark, trustAnchorConfig, currentTime)
    }
}