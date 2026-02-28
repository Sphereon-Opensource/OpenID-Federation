package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

// ==================== List Metadata Policies Endpoint ====================

interface ListMetadataPoliciesEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.list-metadata-policies"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/metadata-policy",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listMetadataPolicies",
            tags = setOf("metadata-policy"),
            summary = "List all metadata policies for the current account"
        )
    }
}

// ==================== Create Metadata Policy Endpoint ====================

interface CreateMetadataPolicyEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.create-metadata-policy"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/metadata-policy",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createMetadataPolicy",
            tags = setOf("metadata-policy"),
            summary = "Create a new metadata policy"
        )
    }
}

// ==================== Delete Metadata Policy Endpoint ====================

interface DeleteMetadataPolicyEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.delete-metadata-policy"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/metadata-policy/{id}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteMetadataPolicy",
            tags = setOf("metadata-policy"),
            summary = "Delete a metadata policy by ID"
        )
    }
}
