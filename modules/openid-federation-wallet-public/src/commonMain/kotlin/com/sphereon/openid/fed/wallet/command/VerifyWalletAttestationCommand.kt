package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable

/**
 * Arguments for verifying a wallet attestation.
 */
data class VerifyWalletAttestationArgs(
    val walletAttestationJwt: String,
    val trustAnchors: Array<String>,
    val currentTime: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VerifyWalletAttestationArgs) return false
        return walletAttestationJwt == other.walletAttestationJwt &&
                trustAnchors.contentEquals(other.trustAnchors) &&
                currentTime == other.currentTime
    }

    override fun hashCode(): Int {
        var result = walletAttestationJwt.hashCode()
        result = 31 * result + trustAnchors.contentHashCode()
        result = 31 * result + (currentTime?.hashCode() ?: 0)
        return result
    }
}

/**
 * Result of wallet attestation verification.
 */
@Serializable
data class WalletAttestationResult(
    val valid: Boolean,
    val walletProviderIdentifier: String,
    val walletProviderTrustResult: EntityTrustResult
)

/**
 * Service interface for the VerifyWalletAttestation command.
 */
interface VerifyWalletAttestationCommandService {
    suspend fun verifyWalletAttestation(
        walletAttestationJwt: String,
        trustAnchors: Array<String>,
        currentTime: Long? = null
    ): IdkResult<WalletAttestationResult, FederationError>
}

/**
 * Command to verify a Wallet Attestation JWT through federation trust.
 *
 * Decodes the wallet attestation JWT, extracts the issuer (Wallet Provider),
 * evaluates the Wallet Provider's trust in the federation, and verifies
 * the attestation signature against the provider's federation keys.
 */
interface VerifyWalletAttestationCommand :
    Command<VerifyWalletAttestationArgs, WalletAttestationResult, FederationError>,
    VerifyWalletAttestationCommandService {
    companion object {
        const val COMMAND_ID = "fed.wallet.verify-wallet-attestation"
    }
}
