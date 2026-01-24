package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.models.TrustMarkIssuer

data class AddIssuerToTrustMarkTypeArgs(val account: Account, val trustMarkTypeId: String, val issuerIdentifier: String)

interface AddIssuerToTrustMarkTypeCommandService {
    suspend fun addIssuerToTrustMarkType(account: Account, trustMarkTypeId: String, issuerIdentifier: String): IdkResult<TrustMarkIssuer, FederationError>
}

interface AddIssuerToTrustMarkTypeCommand : Command<AddIssuerToTrustMarkTypeArgs, TrustMarkIssuer, FederationError>, AddIssuerToTrustMarkTypeCommandService {
    companion object { const val COMMAND_ID = "fed.services.trust-mark.add-issuer-to-type" }
}
