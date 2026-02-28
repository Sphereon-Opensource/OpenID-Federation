package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

// ==================== Get Entity Statement Endpoint ====================

interface GetEntityStatementEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.get-entity-statement"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/entity-statement",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "getEntityStatement",
            tags = setOf("entity-statement"),
            summary = "Get entity configuration statement for the current account"
        )
    }
}

// ==================== Publish Entity Statement Endpoint ====================

interface PublishEntityStatementEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.publish-entity-statement"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/entity-statement",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "publishEntityStatement",
            tags = setOf("entity-statement"),
            summary = "Publish entity configuration statement"
        )
    }
}
