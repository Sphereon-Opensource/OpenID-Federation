package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.TrustMark

data class GetTrustMarksForAccountArgs(val account: Account)

interface GetTrustMarksForAccountCommandService {
    suspend fun getTrustMarksForAccount(account: Account): IdkResult<List<TrustMark>, FederationError>
}

interface GetTrustMarksForAccountCommand : Command<GetTrustMarksForAccountArgs, List<TrustMark>, FederationError>, GetTrustMarksForAccountCommandService {
    companion object { const val COMMAND_ID = "fed.services.trust-mark.get-for-account" }
}
