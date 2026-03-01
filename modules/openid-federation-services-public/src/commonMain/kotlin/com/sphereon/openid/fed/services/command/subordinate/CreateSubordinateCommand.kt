package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.CreateSubordinate
import com.sphereon.openid.fed.openapi.models.Subordinate

data class CreateSubordinateArgs(val tenantId: String, val subordinateDTO: CreateSubordinate)

interface CreateSubordinateCommand : ServiceCommand<CreateSubordinateArgs, Subordinate>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.subordinate.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/subordinates",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "createSubordinate",
            tags = setOf("subordinates"),
            summary = "Create a subordinate"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
