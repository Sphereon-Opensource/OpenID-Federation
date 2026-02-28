package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.MetadataPolicy
import kotlinx.serialization.json.JsonElement

data class CreateMetadataPolicyArgs(
    val account: Account,
    val key: String,
    val policy: JsonElement
)

interface CreateMetadataPolicyCommand : ServiceCommand<CreateMetadataPolicyArgs, MetadataPolicy>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.metadata-policy.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/metadata-policies",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "createMetadataPolicy",
            tags = setOf("metadata-policy"),
            summary = "Create a metadata policy"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
