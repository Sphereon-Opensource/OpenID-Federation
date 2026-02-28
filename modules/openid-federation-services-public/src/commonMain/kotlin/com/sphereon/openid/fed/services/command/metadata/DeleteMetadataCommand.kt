package com.sphereon.openid.fed.services.command.metadata

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.Metadata

data class DeleteMetadataArgs(
    val account: Account,
    val id: String
)

interface DeleteMetadataCommand : ServiceCommand<DeleteMetadataArgs, Metadata>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.metadata.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/metadata/{id}",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "deleteMetadata",
            tags = setOf("metadata"),
            summary = "Delete metadata"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
