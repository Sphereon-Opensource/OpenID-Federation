package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

// ==================== List Metadata Endpoint ====================

interface ListMetadataEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.metadata.list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/metadata",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listMetadata",
            tags = setOf("metadata"),
            summary = "List all metadata for the current account"
        )
    }
}

// ==================== Create Metadata Endpoint ====================

interface CreateMetadataEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.metadata.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/metadata",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createMetadata",
            tags = setOf("metadata"),
            summary = "Create new metadata entry"
        )
    }
}

// ==================== Delete Metadata Endpoint ====================

interface DeleteMetadataEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.metadata.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/metadata/{id}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteMetadata",
            tags = setOf("metadata"),
            summary = "Delete metadata entry by ID"
        )
    }
}
