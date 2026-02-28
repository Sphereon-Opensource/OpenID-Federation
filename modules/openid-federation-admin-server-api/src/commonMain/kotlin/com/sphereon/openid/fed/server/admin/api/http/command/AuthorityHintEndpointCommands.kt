package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

// ==================== List Authority Hints Endpoint ====================

interface ListAuthorityHintsEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.list-authority-hints"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/authority-hints",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listAuthorityHints",
            tags = setOf("authority-hints"),
            summary = "List all authority hints for the current account"
        )
    }
}

// ==================== Create Authority Hint Endpoint ====================

interface CreateAuthorityHintEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.create-authority-hint"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/authority-hints",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createAuthorityHint",
            tags = setOf("authority-hints"),
            summary = "Create a new authority hint"
        )
    }
}

// ==================== Delete Authority Hint Endpoint ====================

interface DeleteAuthorityHintEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.delete-authority-hint"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/authority-hints/{id}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteAuthorityHint",
            tags = setOf("authority-hints"),
            summary = "Delete an authority hint by ID"
        )
    }
}
