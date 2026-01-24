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
 * @param trustAnchorConfig The Trust Anchor's Entity Configuration.
 * @param currentTime Optional timestamp for validation (defaults to current time).
 */
data class VerifyTrustMarkArgs(
    val trustMark: String,
    val trustAnchorConfig: EntityConfigurationStatement,
    val currentTime: Long? = null
)

/**
 * Service interface for verifying trust marks.
 */
interface VerifyTrustMarkCommandService {
    /**
     * Verifies a Trust Mark according to the OpenID Federation specification.
     *
     * @param trustMark The Trust Mark JWT string to validate.
     * @param trustAnchorConfig The Trust Anchor's Entity Configuration.
     * @param currentTime Optional timestamp for validation (defaults to current time).
     * @return IdkResult containing the TrustMarkValidationResponse or an error.
     */
    suspend fun verifyTrustMark(
        trustMark: String,
        trustAnchorConfig: EntityConfigurationStatement,
        currentTime: Long? = null
    ): IdkResult<TrustMarkValidationResponse, FederationError>
}

/**
 * Command to verify a trust mark.
 */
interface VerifyTrustMarkCommand :
    Command<VerifyTrustMarkArgs, TrustMarkValidationResponse, FederationError>,
    VerifyTrustMarkCommandService {

    companion object {
        const val COMMAND_ID = "fed.client.trustMark.verify"
    }
}
