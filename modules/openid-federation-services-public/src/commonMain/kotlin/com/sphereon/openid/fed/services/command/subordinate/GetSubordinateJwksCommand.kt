package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.SubordinateJwk

data class GetSubordinateJwksArgs(val tenantId: String, val id: String)

interface GetSubordinateJwksCommand : ServiceCommand<GetSubordinateJwksArgs, Array<SubordinateJwk>, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.subordinate.get-jwks"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/subordinates/{id}/keys",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "listSubordinateKeys",
            tags = setOf("subordinates"),
            summary = "List subordinate keys"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
