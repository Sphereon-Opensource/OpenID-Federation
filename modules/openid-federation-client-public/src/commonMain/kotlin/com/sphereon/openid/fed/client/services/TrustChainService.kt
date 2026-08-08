package com.sphereon.openid.fed.client.services

import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommandService
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommandService
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.TrustChainResolveResponse
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse

/**
 * Service interface for trust chain operations.
 *
 * Provides functionality to resolve and verify trust chains according to
 * the OpenID Federation specification.
 *
 * This service aggregates all trust chain-related commands and provides both
 * direct method access and command-based access patterns.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface TrustChainService :
    ResolveTrustChainCommandService,
    VerifyTrustChainCommandService {

    /**
     * Provides access to individual trust chain commands for advanced use cases
     * like composition, chaining, or extension-based processing.
     */
    val commands: Commands

    /**
     * Container interface for all trust chain-related commands.
     */
    interface Commands {
        val resolveTrustChain: ResolveTrustChainCommand
        val verifyTrustChain: VerifyTrustChainCommand
    }

    /**
     * Builds a trust chain for the given entity identifier using the provided trust anchors.
     * It returns the first trust chain that is successfully resolved.
     *
     * @param entityIdentifier The entity identifier for which to build the trust chain.
     * @param trustAnchors The trust anchors to use for building the trust chain.
     * @param maxDepth The maximum depth to search for trust chain links.
     * @return FederationResult containing the TrustChainResolveResponse or an error.
     */
    override suspend fun resolveTrustChain(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int
    ): FederationResult<TrustChainResolveResponse>

    /**
     * Verifies the trust chain (OIDFed 1.1 §10.2).
     *
     * @param trustChain The trust chain to verify.
     * @param trustAnchor The Trust Anchor Entity Identifier. Optional.
     * @param currentTime Validation time (epoch seconds).
     * @param trustAnchorPublicKeys Optional out-of-band Trust Anchor public keys.
     * @return FederationResult containing the VerifyTrustChainResponse or an error.
     */
    override suspend fun verifyTrustChain(
        trustChain: Array<String>,
        trustAnchor: String?,
        currentTime: Long?,
        trustAnchorPublicKeys: List<Jwk>?
    ): FederationResult<VerifyTrustChainResponse>
}
