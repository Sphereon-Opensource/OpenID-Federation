package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError

data class FetchSubordinateStatementArgs(val iss: String, val sub: String)

interface FetchSubordinateStatementCommandService {
    suspend fun fetchSubordinateStatement(iss: String, sub: String): IdkResult<String, FederationError>
}

interface FetchSubordinateStatementCommand : Command<FetchSubordinateStatementArgs, String, FederationError>, FetchSubordinateStatementCommandService {
    companion object { const val COMMAND_ID = "fed.services.subordinate.fetch-statement" }
}
