package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod

// ==================== Fetch Subordinate Statement (Root) Endpoint ====================

interface FetchSubordinateRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.fetch"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/fetch",
            produces = setOf(EntityStatementJwtMediaType),
            operationId = "fetchSubordinateStatement",
            tags = setOf("federation"),
            summary = "Fetch subordinate entity statement"
        )
    }
}

// ==================== Fetch Subordinate Statement (Per Account) Endpoint ====================

interface FetchSubordinateAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account-fetch"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{username}/fetch",
            produces = setOf(EntityStatementJwtMediaType),
            operationId = "fetchAccountSubordinateStatement",
            tags = setOf("federation"),
            summary = "Fetch subordinate entity statement for a specific account"
        )
    }
}
