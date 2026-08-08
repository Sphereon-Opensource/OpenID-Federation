package com.sphereon.openid.fed.services.command.subordinateConstraint

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.SubordinateConstraints

data class GetSubordinateConstraintsArgs(val tenantId: String, val subordinateId: String)

interface GetSubordinateConstraintsCommand : ServiceCommand<GetSubordinateConstraintsArgs, SubordinateConstraints, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.subordinate-constraint.get"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/subordinates/{subordinateId}/constraints",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "getSubordinateConstraints",
            tags = setOf("constraints"),
            summary = "Get constraints for a subordinate"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
