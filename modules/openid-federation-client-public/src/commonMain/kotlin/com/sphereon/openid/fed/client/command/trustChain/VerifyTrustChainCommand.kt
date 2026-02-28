package com.sphereon.openid.fed.client.command.trustChain

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse

/**
 * Arguments for the VerifyTrustChain command.
 *
 * @param trustChain The trust chain to verify.
 * @param trustAnchor The trust anchor to use for verification. Optional.
 * @param currentTime The current time to use for verification. Defaults to the current epoch time in seconds.
 */
data class VerifyTrustChainArgs(
    val trustChain: Array<String>,
    val trustAnchor: String?,
    val currentTime: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as VerifyTrustChainArgs

        if (!trustChain.contentEquals(other.trustChain)) return false
        if (trustAnchor != other.trustAnchor) return false
        if (currentTime != other.currentTime) return false

        return true
    }

    override fun hashCode(): Int {
        var result = trustChain.contentHashCode()
        result = 31 * result + (trustAnchor?.hashCode() ?: 0)
        result = 31 * result + (currentTime?.hashCode() ?: 0)
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
     * @param trustAnchor The trust anchor to use for verification. Optional.
     * @param currentTime The current time to use for verification. Defaults to the current epoch time in seconds.
     * @return IdkResult containing the VerifyTrustChainResponse or an error.
     */
    suspend fun verifyTrustChain(
        trustChain: Array<String>,
        trustAnchor: String?,
        currentTime: Long? = null
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
