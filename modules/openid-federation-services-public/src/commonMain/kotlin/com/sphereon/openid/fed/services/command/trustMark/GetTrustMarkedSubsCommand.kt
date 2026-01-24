package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.TrustMarkListRequest

data class GetTrustMarkedSubsArgs(val account: Account, val request: TrustMarkListRequest)

interface GetTrustMarkedSubsCommandService {
    suspend fun getTrustMarkedSubs(account: Account, request: TrustMarkListRequest): IdkResult<Array<String>, FederationError>
}

interface GetTrustMarkedSubsCommand : Command<GetTrustMarkedSubsArgs, Array<String>, FederationError>, GetTrustMarkedSubsCommandService {
    companion object { const val COMMAND_ID = "fed.services.trust-mark.get-marked-subs" }
}
