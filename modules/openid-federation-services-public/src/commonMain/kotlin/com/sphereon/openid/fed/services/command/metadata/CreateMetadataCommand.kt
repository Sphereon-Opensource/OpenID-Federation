package com.sphereon.openid.fed.services.command.metadata

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.Metadata
import kotlinx.serialization.json.JsonElement

data class CreateMetadataArgs(
    val tenantId: String,
    val key: String,
    val metadata: JsonElement
)

interface CreateMetadataCommand : ServiceCommand<CreateMetadataArgs, Metadata, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.metadata.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/metadata",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "createMetadata",
            tags = setOf("metadata"),
            summary = "Create metadata"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
