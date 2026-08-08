package com.sphereon.openid.fed.services.command.subordinateConstraint

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.Constraints
import com.sphereon.openid.fed.openapi.models.SubordinateConstraints

data class SetSubordinateConstraintsArgs(val tenantId: String, val subordinateId: String, val constraints: Constraints)

interface SetSubordinateConstraintsCommand : ServiceCommand<SetSubordinateConstraintsArgs, SubordinateConstraints, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.subordinate-constraint.set"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.PUT,
            pathPattern = "/subordinates/{subordinateId}/constraints",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "setSubordinateConstraints",
            tags = setOf("constraints"),
            summary = "Set constraints for a subordinate"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
