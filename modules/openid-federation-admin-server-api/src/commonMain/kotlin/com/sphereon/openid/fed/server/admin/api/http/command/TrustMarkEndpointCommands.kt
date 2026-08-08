package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

// ==================== List Trust Marks Endpoint ====================

interface ListTrustMarksEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.list-trust-marks"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-marks",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listTrustMarks",
            tags = setOf("trust-marks"),
            summary = "List all trust marks for the current account"
        )
    }
}

// ==================== Create Trust Mark Endpoint ====================

interface CreateTrustMarkEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.create-trust-mark"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/trust-marks",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createTrustMark",
            tags = setOf("trust-marks"),
            summary = "Create a new trust mark"
        )
    }
}

// ==================== Delete Trust Mark Endpoint ====================

interface DeleteTrustMarkEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.delete-trust-mark"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/trust-marks/{trustMarkId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteTrustMark",
            tags = setOf("trust-marks"),
            summary = "Delete a trust mark by ID"
        )
    }
}
