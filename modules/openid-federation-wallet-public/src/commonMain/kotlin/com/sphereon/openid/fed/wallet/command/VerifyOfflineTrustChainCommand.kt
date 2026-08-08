package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.client.helpers.OfflineTrustChainPolicy
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Jwk
import kotlinx.serialization.Serializable

/**
 * Arguments for offline Trust Chain verification (wallet profile §9 / OIDFed §4.3).
 *
 * The Trust Chain is provided (e.g. JWT `trust_chain` header) and verified without
 * Federation API discovery when Trust Anchor public keys are supplied out-of-band.
 *
 * @param policy Optional override of [OfflineTrustChainPolicy]. When null, uses
 *   [com.sphereon.openid.fed.client.context.FederationContext.offlineTrustChainPolicy].
 */
data class VerifyOfflineTrustChainArgs(
    val trustChain: Array<String>,
    val trustAnchor: String,
    val trustAnchorPublicKeys: List<Jwk>,
    val currentTime: Long? = null,
    val policy: OfflineTrustChainPolicy? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VerifyOfflineTrustChainArgs) return false
        return trustChain.contentEquals(other.trustChain) &&
            trustAnchor == other.trustAnchor &&
            trustAnchorPublicKeys == other.trustAnchorPublicKeys &&
            currentTime == other.currentTime &&
            policy == other.policy
    }

    override fun hashCode(): Int {
        var result = trustChain.contentHashCode()
        result = 31 * result + trustAnchor.hashCode()
        result = 31 * result + trustAnchorPublicKeys.hashCode()
        result = 31 * result + (currentTime?.hashCode() ?: 0)
        result = 31 * result + (policy?.hashCode() ?: 0)
        return result
    }
}

@Serializable
data class OfflineTrustChainResult(
    val valid: Boolean,
    val trustAnchor: String,
    val chainLength: Int,
    val detail: String? = null,
    /** Age of the chain snapshot in seconds (`now - max(iat)`), when computable. */
    val ageSeconds: Long? = null,
    /** Seconds until earliest statement `exp`, when computable. */
    val remainingSeconds: Long? = null,
)

interface VerifyOfflineTrustChainCommandService {
    suspend fun verifyOfflineTrustChain(
        trustChain: Array<String>,
        trustAnchor: String,
        trustAnchorPublicKeys: List<Jwk>,
        currentTime: Long? = null,
        policy: OfflineTrustChainPolicy? = null,
    ): IdkResult<OfflineTrustChainResult, FederationError>
}

/**
 * Verifies a pre-built Trust Chain offline using out-of-band Trust Anchor keys.
 *
 * Use when a JWT carries a `trust_chain` header (wallet attestation, request object, credential)
 * and live Federation Entity Discovery is unavailable or undesirable.
 *
 * Applies optional [OfflineTrustChainPolicy] (max age / min remaining lifetime) after crypto verify.
 */
interface VerifyOfflineTrustChainCommand :
    Command<VerifyOfflineTrustChainArgs, OfflineTrustChainResult, FederationError>,
    VerifyOfflineTrustChainCommandService {
    companion object {
        const val COMMAND_ID = "fed.wallet.verify-offline-trust-chain"
    }
}
