package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.TrustMarkStatusRequest

/**
 * The Trust Mark Status Response (OpenID Federation 1.1 §8.4.2): a JWT of type `trust-mark-status-response+jwt`
 * signed by the Trust Mark issuer, stating whether the Trust Mark is active, expired, revoked or invalid.
 */
data class GetSignedTrustMarkStatusJwtArgs(
    val tenantId: String,
    val request: TrustMarkStatusRequest,
    /** The Trust Mark under evaluation, when the caller submitted it. */
    val trustMarkJwt: String? = null,
)

interface GetSignedTrustMarkStatusJwtCommand : ServiceCommand<GetSignedTrustMarkStatusJwtArgs, String, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.get-signed-status-jwt"
    }
}
