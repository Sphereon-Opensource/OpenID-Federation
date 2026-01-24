package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.models.TrustMarkIssuer

data class GetIssuersForTrustMarkTypeArgs(val account: Account, val trustMarkTypeId: String)

interface GetIssuersForTrustMarkTypeCommandService {
    suspend fun getIssuersForTrustMarkType(account: Account, trustMarkTypeId: String): IdkResult<Array<TrustMarkIssuer>, FederationError>
}

interface GetIssuersForTrustMarkTypeCommand : Command<GetIssuersForTrustMarkTypeArgs, Array<TrustMarkIssuer>, FederationError>, GetIssuersForTrustMarkTypeCommandService {
    companion object { const val COMMAND_ID = "fed.services.trust-mark.get-issuers-for-type" }
}
