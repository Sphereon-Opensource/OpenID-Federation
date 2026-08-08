package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.TrustMarkStatusRequest

/**
 * @param trustMarkJwt Optional full Trust Mark JWT (OIDFed §8.4.1 `trust_mark` parameter).
 *   When set, status is evaluated against this exact JWT.
 */
data class GetTrustMarkStatusArgs(
    val tenantId: String,
    val request: TrustMarkStatusRequest,
    val trustMarkJwt: String? = null,
)

/**
 * Status values for Trust Mark Status Response (OIDFed 1.1 §8.4.2).
 */
enum class TrustMarkStatusValue(val wire: String) {
    ACTIVE("active"),
    EXPIRED("expired"),
    REVOKED("revoked"),
    INVALID("invalid"),
}

data class TrustMarkStatusDetail(
    val status: TrustMarkStatusValue,
    /** Trust Mark JWT to echo in the status response (submitted JWT when available). */
    val trustMarkJwt: String,
)

interface GetTrustMarkStatusCommand : ServiceCommand<GetTrustMarkStatusArgs, TrustMarkStatusDetail, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.get-status"
    }
}
