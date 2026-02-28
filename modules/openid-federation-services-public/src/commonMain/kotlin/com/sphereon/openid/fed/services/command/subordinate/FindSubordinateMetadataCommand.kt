package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.SubordinateMetadata

data class FindSubordinateMetadataArgs(val account: Account, val subordinateId: String)

interface FindSubordinateMetadataCommand : ServiceCommand<FindSubordinateMetadataArgs, Array<SubordinateMetadata>>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.subordinate.find-metadata"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/subordinates/{id}/metadata",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "listSubordinateMetadata",
            tags = setOf("subordinates"),
            summary = "List subordinate metadata"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
