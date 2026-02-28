package com.sphereon.openid.fed.client.command.trustChain

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.TrustChainResolveResponse

/**
 * Arguments for the ResolveTrustChain command.
 *
 * @param entityIdentifier The entity identifier for which to build the trust chain.
 * @param trustAnchors The trust anchors to use for building the trust chain.
 * @param maxDepth The maximum depth to search for trust chain links.
 */
data class ResolveTrustChainArgs(
    val entityIdentifier: String,
    val trustAnchors: Array<String>,
    val maxDepth: Int = 5
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ResolveTrustChainArgs

        if (entityIdentifier != other.entityIdentifier) return false
        if (!trustAnchors.contentEquals(other.trustAnchors)) return false
        if (maxDepth != other.maxDepth) return false

        return true
    }

    override fun hashCode(): Int {
        var result = entityIdentifier.hashCode()
        result = 31 * result + trustAnchors.contentHashCode()
        result = 31 * result + maxDepth
        return result
    }
}

/**
 * Service interface for resolving trust chains.
 */
interface ResolveTrustChainCommandService {
    /**
     * Builds a trust chain for the given entity identifier using the provided trust anchors.
     * It returns the first trust chain that is successfully resolved.
     *
     * @param entityIdentifier The entity identifier for which to build the trust chain.
     * @param trustAnchors The trust anchors to use for building the trust chain.
     * @param maxDepth The maximum depth to search for trust chain links.
     * @return IdkResult containing the TrustChainResolveResponse or an error.
     */
    suspend fun resolveTrustChain(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int = 5
    ): IdkResult<TrustChainResolveResponse, FederationError>
}

/**
 * Command to resolve a trust chain for an entity.
 */
interface ResolveTrustChainCommand :
    Command<ResolveTrustChainArgs, TrustChainResolveResponse, FederationError>,
    ResolveTrustChainCommandService {

    companion object {
        const val COMMAND_ID = "fed.client.resolve-trust-chain"
    }
}
