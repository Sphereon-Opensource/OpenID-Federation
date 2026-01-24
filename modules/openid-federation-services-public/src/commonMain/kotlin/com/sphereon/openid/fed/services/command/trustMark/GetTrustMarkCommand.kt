package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.TrustMarkRequest

data class GetTrustMarkArgs(val account: Account, val request: TrustMarkRequest)

interface GetTrustMarkCommandService {
    suspend fun getTrustMark(account: Account, request: TrustMarkRequest): IdkResult<String, FederationError>
}

interface GetTrustMarkCommand : Command<GetTrustMarkArgs, String, FederationError>, GetTrustMarkCommandService {
    companion object { const val COMMAND_ID = "fed.services.trust-mark.get" }
}
