package com.sphereon.openid.fed.client.command.trustChain

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse

/**
 * Arguments for the VerifyTrustChain command.
 *
 * @param trustChain The trust chain to verify (leaf EC first, Trust Anchor EC last).
 * @param trustAnchor The Trust Anchor Entity Identifier. Optional but recommended.
 * @param currentTime Validation time in epoch seconds (defaults to now).
 * @param trustAnchorPublicKeys Optional out-of-band public keys for the Trust Anchor.
 *   When non-empty, the Trust Anchor Entity Configuration signature MUST verify with one of
 *   these keys (OIDFed 1.1 §4 / §10.2 — TA keys distributed securely, not only via self-JWKS).
 */
data class VerifyTrustChainArgs(
    val trustChain: Array<String>,
    val trustAnchor: String?,
    val currentTime: Long? = null,
    val trustAnchorPublicKeys: List<Jwk>? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as VerifyTrustChainArgs

        if (!trustChain.contentEquals(other.trustChain)) return false
        if (trustAnchor != other.trustAnchor) return false
        if (currentTime != other.currentTime) return false
        if (trustAnchorPublicKeys != other.trustAnchorPublicKeys) return false

        return true
    }

    override fun hashCode(): Int {
        var result = trustChain.contentHashCode()
        result = 31 * result + (trustAnchor?.hashCode() ?: 0)
        result = 31 * result + (currentTime?.hashCode() ?: 0)
        result = 31 * result + (trustAnchorPublicKeys?.hashCode() ?: 0)
        return result
    }
}

/**
 * Service interface for verifying trust chains.
 */
interface VerifyTrustChainCommandService {
    /**
     * Verifies the trust chain.
     *
     * @param trustChain The trust chain to verify.
     * @param trustAnchor The trust anchor Entity Identifier. Optional.
     * @param currentTime Validation time (epoch seconds). Defaults to now.
     * @param trustAnchorPublicKeys Optional out-of-band Trust Anchor public keys.
     * @return IdkResult containing the VerifyTrustChainResponse or an error.
     */
    suspend fun verifyTrustChain(
        trustChain: Array<String>,
        trustAnchor: String?,
        currentTime: Long? = null,
        trustAnchorPublicKeys: List<Jwk>? = null
    ): IdkResult<VerifyTrustChainResponse, FederationError>
}

/**
 * Command to verify a trust chain.
 */
interface VerifyTrustChainCommand :
    Command<VerifyTrustChainArgs, VerifyTrustChainResponse, FederationError>,
    VerifyTrustChainCommandService {

    companion object {
        const val COMMAND_ID = "fed.client.verify-trust-chain"
    }
}
