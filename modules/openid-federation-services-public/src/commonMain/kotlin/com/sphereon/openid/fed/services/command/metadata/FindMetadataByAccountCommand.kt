package com.sphereon.openid.fed.services.command.metadata

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.Metadata

data class FindMetadataByAccountArgs(
    val tenantId: String
)

interface FindMetadataByAccountCommand : ServiceCommand<FindMetadataByAccountArgs, List<Metadata>>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.metadata.find-by-account"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/metadata",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "listMetadata",
            tags = setOf("metadata"),
            summary = "List metadata"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
