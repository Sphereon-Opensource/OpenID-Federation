package com.sphereon.openid.fed.client.command.trustMark

import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse

/**
 * A Trust Mark delegation to validate on its own, before an issuer embeds it in the Trust Marks it issues.
 *
 * @param delegation The Trust Mark delegation JWT (`trust-mark-delegation+jwt`).
 * @param trustMarkType The Trust Mark type the delegation must be for.
 * @param issuer The Entity Identifier of the Trust Mark issuer the delegation must name as `sub`.
 * @param trustAnchorConfig The Entity Configuration of the Trust Anchor whose `trust_mark_owners` names the owner.
 */
data class VerifyTrustMarkDelegationArgs(
    val delegation: String,
    val trustMarkType: String,
    val issuer: String,
    val trustAnchorConfig: EntityConfigurationStatement,
    val currentTime: Long? = null,
)

/**
 * Validates a Trust Mark delegation (OpenID Federation 1.1 section 7.2.2) against the owner the Trust Anchor records for
 * the type in `trust_mark_owners`. A type the Trust Anchor records no owner for has no delegation to validate.
 */
interface VerifyTrustMarkDelegationCommand : Command<VerifyTrustMarkDelegationArgs, TrustMarkValidationResponse, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.client.verify-trust-mark-delegation"
    }
}
