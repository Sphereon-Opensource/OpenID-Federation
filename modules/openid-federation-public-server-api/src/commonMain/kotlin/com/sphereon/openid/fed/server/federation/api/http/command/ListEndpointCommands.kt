package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

// ==================== List Subordinates (Root) Endpoint ====================

interface ListSubordinatesRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/list",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listSubordinates",
            tags = setOf("federation"),
            summary = "List subordinate entities"
        )
    }
}

// ==================== List Subordinates (Per Account) Endpoint ====================

interface ListSubordinatesAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account-list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{username}/list",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listAccountSubordinates",
            tags = setOf("federation"),
            summary = "List subordinate entities for a specific account"
        )
    }
}
