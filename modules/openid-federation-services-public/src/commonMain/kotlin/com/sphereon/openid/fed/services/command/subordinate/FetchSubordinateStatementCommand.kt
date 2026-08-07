package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.service.ServiceCommand

data class FetchSubordinateStatementArgs(val iss: String, val sub: String)

interface FetchSubordinateStatementCommand : ServiceCommand<FetchSubordinateStatementArgs, String, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.subordinate.fetch-statement"
    }
}
