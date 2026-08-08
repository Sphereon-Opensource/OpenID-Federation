package com.sphereon.openid.fed.client.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse

/**
 * Arguments for the VerifyTrustMark command.
 *
 * @param trustMark The Trust Mark JWT string to validate.
 * @param trustAnchorConfig The Trust Anchor's Entity Configuration for the evaluating federation.
 * @param currentTime Optional timestamp for validation (defaults to current time).
 * @param subject Optional Entity Identifier that must match the Trust Mark `sub` claim
 *   (the Entity whose Entity Configuration contains the mark). Required for full §7.3 step 4.
 */
data class VerifyTrustMarkArgs(
    val trustMark: String,
    val trustAnchorConfig: EntityConfigurationStatement,
    val currentTime: Long? = null,
    val subject: String? = null
)

/**
 * Service interface for verifying trust marks.
 */
interface VerifyTrustMarkCommandService {
    /**
     * Verifies a Trust Mark under a specific federation Trust Anchor (OIDFed 1.1 §7.3).
     *
     * Cross-federation: if the mark type is not recognized by [trustAnchorConfig]
     * (`trust_mark_issuers` / `trust_mark_owners`), returns
     * [com.sphereon.openid.fed.core.error.TrustMarkNotRecognizedError]. Callers SHOULD
     * filter such marks out for this federation rather than failing the subject Entity.
     *
     * @param trustMark The Trust Mark JWT string to validate.
     * @param trustAnchorConfig The Trust Anchor's Entity Configuration for the evaluating federation.
     * @param currentTime Optional timestamp for validation (defaults to current time).
     * @param subject Optional Entity Identifier expected in the Trust Mark `sub` claim.
     * @return IdkResult containing the TrustMarkValidationResponse or an error.
     */
    suspend fun verifyTrustMark(
        trustMark: String,
        trustAnchorConfig: EntityConfigurationStatement,
        currentTime: Long? = null,
        subject: String? = null
    ): IdkResult<TrustMarkValidationResponse, FederationError>
}

/**
 * Command to verify a trust mark.
 */
interface VerifyTrustMarkCommand :
    Command<VerifyTrustMarkArgs, TrustMarkValidationResponse, FederationError>,
    VerifyTrustMarkCommandService {

    companion object {
        const val COMMAND_ID = "fed.client.verify-trust-mark"
    }
}
