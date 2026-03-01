package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.MetadataPolicy

data class FindMetadataPolicyByAccountArgs(
    val tenantId: String
)

interface FindMetadataPolicyByAccountCommand : ServiceCommand<FindMetadataPolicyByAccountArgs, List<MetadataPolicy>>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.metadata-policy.find-by-account"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/metadata-policies",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "listMetadataPolicies",
            tags = setOf("metadata-policy"),
            summary = "List metadata policies"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
