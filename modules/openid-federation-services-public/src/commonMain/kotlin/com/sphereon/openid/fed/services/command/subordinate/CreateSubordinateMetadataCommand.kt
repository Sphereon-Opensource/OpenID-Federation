package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.SubordinateMetadata
import kotlinx.serialization.json.JsonElement

data class CreateSubordinateMetadataArgs(
    val account: Account,
    val subordinateId: String,
    val key: String,
    val metadata: JsonElement
)

interface CreateSubordinateMetadataCommand : ServiceCommand<CreateSubordinateMetadataArgs, SubordinateMetadata>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.subordinate.create-metadata"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/subordinates/{id}/metadata",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "createSubordinateMetadata",
            tags = setOf("subordinates"),
            summary = "Create subordinate metadata"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
