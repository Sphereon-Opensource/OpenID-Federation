package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.SubordinateStatement

data class GetSubordinateStatementArgs(val account: Account, val id: String)

interface GetSubordinateStatementCommandService {
    suspend fun getSubordinateStatement(account: Account, id: String): IdkResult<SubordinateStatement, FederationError>
}

interface GetSubordinateStatementCommand : Command<GetSubordinateStatementArgs, SubordinateStatement, FederationError>, GetSubordinateStatementCommandService {
    companion object { const val COMMAND_ID = "fed.services.subordinate.get-statement" }
}
