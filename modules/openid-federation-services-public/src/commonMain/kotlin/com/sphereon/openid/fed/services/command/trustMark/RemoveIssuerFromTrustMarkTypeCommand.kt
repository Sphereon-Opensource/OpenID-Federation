package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.models.TrustMarkIssuer

data class RemoveIssuerFromTrustMarkTypeArgs(val account: Account, val trustMarkTypeId: String, val issuerId: String)

interface RemoveIssuerFromTrustMarkTypeCommandService {
    suspend fun removeIssuerFromTrustMarkType(account: Account, trustMarkTypeId: String, issuerId: String): IdkResult<TrustMarkIssuer, FederationError>
}

interface RemoveIssuerFromTrustMarkTypeCommand : Command<RemoveIssuerFromTrustMarkTypeArgs, TrustMarkIssuer, FederationError>, RemoveIssuerFromTrustMarkTypeCommandService {
    companion object { const val COMMAND_ID = "fed.services.trust-mark.remove-issuer-from-type" }
}
