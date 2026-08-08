package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

// ==================== Fetch Subordinate Statement GET (Root) Endpoint ====================

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

// ==================== Fetch Subordinate Statement POST (Root) Endpoint ====================

interface PostFetchSubordinateRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.post-fetch"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/fetch",
            consumes = setOf(MediaType.ApplicationFormUrlEncoded),
            produces = setOf(EntityStatementJwtMediaType),
            operationId = "fetchSubordinateStatementPost",
            tags = setOf("federation"),
            summary = "Fetch subordinate entity statement (POST)"
        )
    }
}

// ==================== Fetch Subordinate Statement GET (Per Account) Endpoint ====================

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

// ==================== Fetch Subordinate Statement POST (Per Account) Endpoint ====================

interface PostFetchSubordinateAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.post-account-fetch"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/{username}/fetch",
            consumes = setOf(MediaType.ApplicationFormUrlEncoded),
            produces = setOf(EntityStatementJwtMediaType),
            operationId = "fetchAccountSubordinateStatementPost",
            tags = setOf("federation"),
            summary = "Fetch subordinate entity statement for a specific account (POST)"
        )
    }
}
