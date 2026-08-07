package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.MetadataPolicy

data class DeleteMetadataPolicyArgs(
    val tenantId: String,
    val id: String
)

interface DeleteMetadataPolicyCommand : ServiceCommand<DeleteMetadataPolicyArgs, MetadataPolicy, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.metadata-policy.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/metadata-policies/{id}",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "deleteMetadataPolicy",
            tags = setOf("metadata-policy"),
            summary = "Delete a metadata policy"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
