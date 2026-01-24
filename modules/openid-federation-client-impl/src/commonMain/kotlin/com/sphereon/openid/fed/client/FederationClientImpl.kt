package com.sphereon.openid.fed.client

import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.ktor.http.client.provider.HttpClientFactory
import com.sphereon.ktor.http.client.provider.HttpClientOptions
import com.sphereon.openid.fed.core.cache.CacheManager
import com.sphereon.openid.fed.client.cache.FederationCacheRequirements
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.services.EntityConfigurationStatementService
import com.sphereon.openid.fed.client.services.TrustChainService
import com.sphereon.openid.fed.client.services.TrustMarkService
import com.sphereon.openid.fed.httpResolver.HttpMetadata
import com.sphereon.openid.fed.httpResolver.HttpResolver
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.TrustChainResolveResponse
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse
import io.ktor.client.statement.bodyAsText
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Federation client implementation for reading and validating statements and trust chains.
 *
 * This implementation is provided via DI in session scope. It delegates to the
 * underlying services which use the command pattern for all operations.
 *
 * @param trustChainService Service for trust chain operations
 * @param entityConfigurationService Service for entity configuration operations
 * @param trustMarkService Service for trust mark operations
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FederationClient::class)
class FederationClientImpl(
    private val trustChainService: TrustChainService,
    private val entityConfigurationService: EntityConfigurationStatementService,
    private val trustMarkService: TrustMarkService
) : FederationClient {

    /**
     * Builds a trust chain for the given entity identifier using the provided trust anchors.
     * It returns the first trust chain that is successfully resolved.
     *
     * @param entityIdentifier The entity identifier for which to build the trust chain.
     * @param trustAnchors The trust anchors to use for building the trust chain.
     * @param maxDepth The maximum depth to search for trust chain links.
     * @return A [TrustChainResolveResponse] object containing the resolved trust chain.
     */
    override suspend fun trustChainResolve(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int
    ): TrustChainResolveResponse {
        val result = trustChainService.resolveTrustChain(entityIdentifier, trustAnchors, maxDepth)
        return if (result.isOk) {
            result.value
        } else {
            TrustChainResolveResponse(null, errorMessage = result.error.message.defaultMessage)
        }
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
    override suspend fun trustChainVerify(
        trustChain: Array<String>,
        trustAnchor: String?,
        currentTime: Long?
    ): VerifyTrustChainResponse {
        val result = trustChainService.verifyTrustChain(trustChain, trustAnchor, currentTime)
        return if (result.isOk) {
            result.value
        } else {
            VerifyTrustChainResponse(false, errorMessage = result.error.message.defaultMessage)
        }
    }

    /**
     * Get an Entity Configuration Statement from an entity.
     *
     * @param entityIdentifier The entity identifier for which to get the statement.
     * @return EntityConfigurationStatement containing the entity configuration statement.
     */
    override suspend fun entityConfigurationStatementGet(entityIdentifier: String): EntityConfigurationStatement {
        val result = entityConfigurationService.getEntityConfiguration(entityIdentifier)
        if (result.isOk) {
            return result.value
        } else {
            throw IllegalStateException(result.error.message.defaultMessage)
        }
    }

    /**
     * Verifies a Trust Mark according to the OpenID Federation specification.
     *
     * @param trustMark The Trust Mark JWT string to validate
     * @param trustAnchorConfig The Trust Anchor's Entity Configuration
     * @param currentTime Optional timestamp for validation (defaults to current time)
     * @return TrustMarkValidationResponse containing the validation result and any error message
     */
    override suspend fun trustMarksVerify(
        trustMark: String,
        trustAnchorConfig: EntityConfigurationStatement,
        currentTime: Long?
    ): TrustMarkValidationResponse {
        val result = trustMarkService.verifyTrustMark(trustMark, trustAnchorConfig, currentTime)
        return if (result.isOk) {
            result.value
        } else {
            TrustMarkValidationResponse(false, errorMessage = result.error.message.defaultMessage)
        }
    }
}
