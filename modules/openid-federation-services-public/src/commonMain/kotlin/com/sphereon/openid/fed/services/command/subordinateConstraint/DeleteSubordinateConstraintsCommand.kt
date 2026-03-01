package com.sphereon.openid.fed.services.command.subordinateConstraint

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.SubordinateConstraints

data class DeleteSubordinateConstraintsArgs(val tenantId: String, val subordinateId: String)

interface DeleteSubordinateConstraintsCommand : ServiceCommand<DeleteSubordinateConstraintsArgs, SubordinateConstraints>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.subordinate-constraint.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/subordinates/{subordinateId}/constraints",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "deleteSubordinateConstraints",
            tags = setOf("constraints"),
            summary = "Delete constraints for a subordinate"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
