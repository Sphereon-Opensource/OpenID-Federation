package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.TrustMarkStatusRequest

data class GetTrustMarkStatusArgs(val account: Account, val request: TrustMarkStatusRequest)

interface GetTrustMarkStatusCommandService {
    suspend fun getTrustMarkStatus(account: Account, request: TrustMarkStatusRequest): IdkResult<Boolean, FederationError>
}

interface GetTrustMarkStatusCommand : Command<GetTrustMarkStatusArgs, Boolean, FederationError>, GetTrustMarkStatusCommandService {
    companion object { const val COMMAND_ID = "fed.services.trust-mark.get-status" }
}
