package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.SubordinateJwk

data class CreateSubordinateJwkArgs(val tenantId: String, val id: String, val jwk: Jwk)

interface CreateSubordinateJwkCommand : ServiceCommand<CreateSubordinateJwkArgs, SubordinateJwk, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.subordinate.create-jwk"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/subordinates/{id}/keys",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "createSubordinateKey",
            tags = setOf("subordinates"),
            summary = "Create a subordinate key"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
