package com.sphereon.openid.fed.client

import com.sphereon.core.compat.JsExportCompat
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.TrustChainResolveResponse
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse

/**
 * Federation client interface for reading and validating statements and trust chains.
 *
 * Implementations are provided via DI (session-scoped).
 */
@JsExportCompat
interface FederationClient {

    /**
     * Builds a trust chain for the given entity identifier using the provided trust anchors.
     * It returns the first trust chain that is successfully resolved.
     *
     * @param entityIdentifier The entity identifier for which to build the trust chain.
     * @param trustAnchors The trust anchors to use for building the trust chain.
     * @param maxDepth The maximum depth to search for trust chain links.
     * @return Ok with [TrustChainResolveResponse] on success, Err with [FederationError] on failure.
     */
    suspend fun trustChainResolve(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int = 5
    ): FederationResult<TrustChainResolveResponse>

    /**
     * Verifies the trust chain.
     *
     * @param trustChain The trust chain to verify.
     * @param trustAnchor The trust anchor to use for verification. Optional.
     * @param currentTime The current time to use for verification. Defaults to the current epoch time in seconds.
     *
     * @return Ok with [VerifyTrustChainResponse] on success, Err with [FederationError] on failure.
     */
    suspend fun trustChainVerify(
        trustChain: Array<String>,
        trustAnchor: String?,
        currentTime: Long?
    ): FederationResult<VerifyTrustChainResponse>

    /**
     * Get an Entity Configuration Statement from an entity.
     *
     * @param entityIdentifier The entity identifier for which to get the statement.
     * @return EntityConfigurationStatement containing the entity configuration statement.
     */
    suspend fun entityConfigurationStatementGet(entityIdentifier: String): FederationResult<EntityConfigurationStatement>

    /**
     * Verifies a Trust Mark according to the OpenID Federation specification.
     *
     * @param trustMark The Trust Mark JWT string to validate
     * @param trustAnchorConfig The Trust Anchor's Entity Configuration
     * @param currentTime Optional timestamp for validation (defaults to current time)
     * @return Ok with [TrustMarkValidationResponse] on success, Err with [FederationError] on failure.
     */
    suspend fun trustMarksVerify(
        trustMark: String,
        trustAnchorConfig: EntityConfigurationStatement,
        currentTime: Long? = null
    ): FederationResult<TrustMarkValidationResponse>
}
