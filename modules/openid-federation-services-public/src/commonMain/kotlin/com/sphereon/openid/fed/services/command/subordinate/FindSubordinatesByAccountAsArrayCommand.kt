package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account

data class FindSubordinatesByAccountAsArrayArgs(val account: Account)

interface FindSubordinatesByAccountAsArrayCommandService {
    suspend fun findSubordinatesByAccountAsArray(account: Account): IdkResult<Array<String>, FederationError>
}

interface FindSubordinatesByAccountAsArrayCommand : Command<FindSubordinatesByAccountAsArrayArgs, Array<String>, FederationError>, FindSubordinatesByAccountAsArrayCommandService {
    companion object { const val COMMAND_ID = "fed.services.subordinate.find-by-account-as-array" }
}
