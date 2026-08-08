package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

// ==================== List Keys Endpoint ====================

interface ListKeysEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.list-keys"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/keys",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listKeys",
            tags = setOf("keys"),
            summary = "List all keys for the current account"
        )
    }
}

// ==================== Create Key Endpoint ====================

interface CreateKeyEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.create-key"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/keys",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createKey",
            tags = setOf("keys"),
            summary = "Create a new key for the current account"
        )
    }
}

// ==================== Revoke Key Endpoint ====================

interface RevokeKeyEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.revoke-key"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/keys/{keyId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "revokeKey",
            tags = setOf("keys"),
            summary = "Revoke a key by ID"
        )
    }
}
