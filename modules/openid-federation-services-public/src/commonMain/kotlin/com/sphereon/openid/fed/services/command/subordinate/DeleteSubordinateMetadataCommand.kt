package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.SubordinateMetadata

data class DeleteSubordinateMetadataArgs(val tenantId: String, val subordinateId: String, val id: String)

interface DeleteSubordinateMetadataCommand : ServiceCommand<DeleteSubordinateMetadataArgs, SubordinateMetadata>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.subordinate.delete-metadata"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/subordinates/{id}/metadata",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "deleteSubordinateMetadata",
            tags = setOf("subordinates"),
            summary = "Delete subordinate metadata"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
