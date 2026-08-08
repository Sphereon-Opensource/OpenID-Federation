package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wallet profile §8.2: periodically re-establish trust with the Wallet Provider
 * (non-revocation via federation Trust Chain).
 */
data class CheckWalletProviderNonRevocationArgs(
    val walletProviderEntityId: String,
    val trustAnchors: Array<String>,
    val requiredTrustMarks: Array<String>? = null,
    val currentTime: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CheckWalletProviderNonRevocationArgs) return false
        return walletProviderEntityId == other.walletProviderEntityId &&
            trustAnchors.contentEquals(other.trustAnchors) &&
            (requiredTrustMarks?.contentEquals(other.requiredTrustMarks ?: emptyArray()) ?: (other.requiredTrustMarks == null)) &&
            currentTime == other.currentTime
    }

    override fun hashCode(): Int {
        var r = walletProviderEntityId.hashCode()
        r = 31 * r + trustAnchors.contentHashCode()
        r = 31 * r + (requiredTrustMarks?.contentHashCode() ?: 0)
        r = 31 * r + (currentTime?.hashCode() ?: 0)
        return r
    }
}

@Serializable
data class WalletProviderNonRevocationResult(
    /** True when a valid Trust Chain to a Trust Anchor was established. */
    val active: Boolean,
    val walletProviderEntityId: String,
    val trustAnchor: String?,
    val trustChain: List<String>,
    val effectiveMetadata: JsonObject?,
    val reason: String? = null,
)

interface CheckWalletProviderNonRevocationCommandService {
    suspend fun checkWalletProviderNonRevocation(
        walletProviderEntityId: String,
        trustAnchors: Array<String>,
        requiredTrustMarks: Array<String>? = null,
        currentTime: Long? = null,
    ): IdkResult<WalletProviderNonRevocationResult, FederationError>
}

interface CheckWalletProviderNonRevocationCommand :
    Command<CheckWalletProviderNonRevocationArgs, WalletProviderNonRevocationResult, FederationError>,
    CheckWalletProviderNonRevocationCommandService {

    companion object {
        const val COMMAND_ID = "fed.wallet.check-wallet-provider-non-revocation"
    }
}
