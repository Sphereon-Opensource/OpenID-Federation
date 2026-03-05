package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Arguments for verifying a credential issuer through federation (DIIP profile).
 */
data class VerifyCredentialIssuerArgs(
    val credentialJwt: String,
    val trustAnchors: Array<String>,
    val currentTime: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VerifyCredentialIssuerArgs) return false
        return credentialJwt == other.credentialJwt &&
                trustAnchors.contentEquals(other.trustAnchors) &&
                currentTime == other.currentTime
    }

    override fun hashCode(): Int {
        var result = credentialJwt.hashCode()
        result = 31 * result + trustAnchors.contentHashCode()
        result = 31 * result + (currentTime?.hashCode() ?: 0)
        return result
    }
}

/**
 * Result of credential issuer verification.
 */
@Serializable
data class CredentialIssuerResult(
    val valid: Boolean,
    val issuerIdentifier: String,
    val issuerIdentifierSource: String,
    val signingKeyId: String?,
    val entityTrustResult: EntityTrustResult
)

/**
 * Service interface for the VerifyCredentialIssuer command.
 */
interface VerifyCredentialIssuerCommandService {
    suspend fun verifyCredentialIssuer(
        credentialJwt: String,
        trustAnchors: Array<String>,
        currentTime: Long? = null
    ): IdkResult<CredentialIssuerResult, FederationError>
}

/**
 * Verify a credential issuer through federation trust and validate the credential
 * signature against the issuer's `vc_issuer` keys.
 *
 * Per DIIP Appendix B:
 * 1. Extract issuer identifier from `fed` claim (or `iss` fallback)
 * 2. Resolve and verify trust chain to a trusted anchor
 * 3. Extract `vc_issuer.jwks` signing keys from entity configuration
 * 4. Verify credential signature against the matching key (by kid)
 */
interface VerifyCredentialIssuerCommand :
    Command<VerifyCredentialIssuerArgs, CredentialIssuerResult, FederationError>,
    VerifyCredentialIssuerCommandService {
    companion object {
        const val COMMAND_ID = "fed.wallet.verify-credential-issuer"
    }
}
