package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.Subordinate

data class DeleteSubordinateArgs(val tenantId: String, val id: String)

interface DeleteSubordinateCommand : ServiceCommand<DeleteSubordinateArgs, Subordinate, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.subordinate.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/subordinates/{id}",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "deleteSubordinate",
            tags = setOf("subordinates"),
            summary = "Delete a subordinate"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
