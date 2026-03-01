package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

// ==================== Get Subordinate Constraints Endpoint ====================

interface GetSubordinateConstraintsEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.get-subordinate-constraints"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/subordinates/{subordinateId}/constraints",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "getSubordinateConstraints",
            tags = setOf("constraints"),
            summary = "Get constraints for a subordinate"
        )
    }
}

// ==================== Set Subordinate Constraints Endpoint ====================

interface SetSubordinateConstraintsEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.set-subordinate-constraints"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.PUT,
            pathPattern = "/subordinates/{subordinateId}/constraints",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "setSubordinateConstraints",
            tags = setOf("constraints"),
            summary = "Set constraints for a subordinate"
        )
    }
}

// ==================== Delete Subordinate Constraints Endpoint ====================

interface DeleteSubordinateConstraintsEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.delete-subordinate-constraints"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/subordinates/{subordinateId}/constraints",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteSubordinateConstraints",
            tags = setOf("constraints"),
            summary = "Delete constraints for a subordinate"
        )
    }
}
