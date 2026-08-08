package com.sphereon.openid.fed.client

import com.sphereon.core.compat.JsExportCompat
import com.sphereon.openid.fed.client.command.discovery.DiscoverEntitiesArgs
import com.sphereon.openid.fed.client.command.discovery.DiscoverEntitiesResult
import com.sphereon.openid.fed.client.command.discovery.ListSubordinatesResult
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.Jwk
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
     *
     * Explores all valid paths (OIDFed 1.1 §10.3) and selects by Trust Anchor preference
     * order then shortest chain. Uses Subordinate `source_endpoint` when known for refresh.
     *
     * When the leaf Entity Configuration publishes `trust_anchor_hints` (OIDFed 1.1 §3.1.2),
     * those hints refine selection: if [trustAnchors] is empty the published hints are used;
     * otherwise configured anchors that also appear in hints are preferred first.
     *
     * @param entityIdentifier The entity identifier for which to build the trust chain.
     * @param trustAnchors The trust anchors to use (preference order for multi-chain selection).
     * @param maxDepth The maximum depth to search for trust chain links.
     * @return Ok with [TrustChainResolveResponse] on success, Err with [FederationError] on failure.
     */
    suspend fun trustChainResolve(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int = 5
    ): FederationResult<TrustChainResolveResponse>

    /**
     * Verifies the trust chain (OIDFed 1.1 §10.2).
     *
     * @param trustChain The trust chain to verify (leaf EC first, TA EC last).
     * @param trustAnchor The Trust Anchor Entity Identifier. Optional but recommended.
     * @param currentTime Validation time (epoch seconds). Defaults to now.
     * @param trustAnchorPublicKeys Optional out-of-band Trust Anchor public keys (root of trust).
     *
     * @return Ok with [VerifyTrustChainResponse] on success, Err with [FederationError] on failure.
     */
    suspend fun trustChainVerify(
        trustChain: Array<String>,
        trustAnchor: String?,
        currentTime: Long?,
        trustAnchorPublicKeys: List<Jwk>? = null
    ): FederationResult<VerifyTrustChainResponse>

    /**
     * Get an Entity Configuration Statement from an entity.
     *
     * @param entityIdentifier The entity identifier for which to get the statement.
     * @return EntityConfigurationStatement containing the entity configuration statement.
     */
    suspend fun entityConfigurationStatementGet(entityIdentifier: String): FederationResult<EntityConfigurationStatement>

    /**
     * Verifies a Trust Mark under a federation Trust Anchor (OIDFed 1.1 §7.3).
     *
     * Marks not recognized by this TA return
     * [com.sphereon.openid.fed.core.error.TrustMarkNotRecognizedError] so callers can
     * filter them for cross-federation Entity Configurations.
     *
     * @param trustMark The Trust Mark JWT string to validate
     * @param trustAnchorConfig The Trust Anchor's Entity Configuration for the evaluating federation
     * @param currentTime Optional timestamp for validation (defaults to current time)
     * @param subject Optional Entity Identifier that must match the Trust Mark `sub` claim
     * @return Ok with [TrustMarkValidationResponse] on success, Err with [FederationError] on failure.
     */
    suspend fun trustMarksVerify(
        trustMark: String,
        trustAnchorConfig: EntityConfigurationStatement,
        currentTime: Long? = null,
        subject: String? = null
    ): FederationResult<TrustMarkValidationResponse>

    /**
     * List Immediate Subordinates of a superior (OIDFed 1.1 §8.2 list endpoint).
     */
    suspend fun listSubordinates(
        superiorEntityId: String,
        entityType: String? = null,
        trustMarked: Boolean? = null,
        trustMarkType: String? = null,
        intermediate: Boolean? = null,
    ): FederationResult<ListSubordinatesResult>

    /**
     * Top-down discovery of entities via list endpoints (wallet profile §8.3 browse pattern).
     *
     * @param startEntityId typically a Trust Anchor Entity Identifier
     * @param entityType e.g. `openid_credential_issuer`
     * @param recursive walk Intermediate list endpoints
     * @param verifyTrust when true, only return entities with a valid Trust Chain to [trustAnchors]
     */
    suspend fun discoverEntities(
        startEntityId: String,
        entityType: String? = null,
        recursive: Boolean = true,
        maxDepth: Int = 5,
        verifyTrust: Boolean = false,
        trustAnchors: Array<String> = emptyArray(),
        maxDepthTrustChain: Int = 5,
    ): FederationResult<DiscoverEntitiesResult>
}
