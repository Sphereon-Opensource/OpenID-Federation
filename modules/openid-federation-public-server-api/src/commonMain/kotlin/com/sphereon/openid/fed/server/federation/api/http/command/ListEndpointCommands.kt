package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

// ==================== List Subordinates GET (Root) Endpoint ====================

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

// ==================== List Subordinates POST (Root) Endpoint ====================

interface PostListSubordinatesRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.post-list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/list",
            consumes = setOf(MediaType.ApplicationFormUrlEncoded),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listSubordinatesPost",
            tags = setOf("federation"),
            summary = "List subordinate entities (POST)"
        )
    }
}

// ==================== List Subordinates GET (Per Account) Endpoint ====================

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

// ==================== List Subordinates POST (Per Account) Endpoint ====================

interface PostListSubordinatesAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.post-account-list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/{username}/list",
            consumes = setOf(MediaType.ApplicationFormUrlEncoded),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listAccountSubordinatesPost",
            tags = setOf("federation"),
            summary = "List subordinate entities for a specific account (POST)"
        )
    }
}
