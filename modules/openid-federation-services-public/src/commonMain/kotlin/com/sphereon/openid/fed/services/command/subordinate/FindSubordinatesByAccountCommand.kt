package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.Subordinate

data class FindSubordinatesByAccountArgs(val account: Account)

interface FindSubordinatesByAccountCommandService {
    suspend fun findSubordinatesByAccount(account: Account): IdkResult<Array<Subordinate>, FederationError>
}

interface FindSubordinatesByAccountCommand : Command<FindSubordinatesByAccountArgs, Array<Subordinate>, FederationError>, FindSubordinatesByAccountCommandService {
    companion object { const val COMMAND_ID = "fed.services.subordinate.find-by-account" }
}
