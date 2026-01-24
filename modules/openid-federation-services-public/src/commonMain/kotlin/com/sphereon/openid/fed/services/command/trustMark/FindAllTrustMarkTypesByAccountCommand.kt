package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.TrustMarkType

data class FindAllTrustMarkTypesByAccountArgs(val account: Account)

interface FindAllTrustMarkTypesByAccountCommandService {
    suspend fun findAllByAccount(account: Account): IdkResult<List<TrustMarkType>, FederationError>
}

interface FindAllTrustMarkTypesByAccountCommand : Command<FindAllTrustMarkTypesByAccountArgs, List<TrustMarkType>, FederationError>, FindAllTrustMarkTypesByAccountCommandService {
    companion object { const val COMMAND_ID = "fed.services.trust-mark.find-all-types-by-account" }
}
