package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

// ==================== List Received Trust Marks Endpoint ====================

interface ListReceivedTrustMarksEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.list-received-trust-marks"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/received-trust-marks",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listReceivedTrustMarks",
            tags = setOf("received-trust-marks"),
            summary = "List all received trust marks for the current account"
        )
    }
}

// ==================== Create Received Trust Mark Endpoint ====================

interface CreateReceivedTrustMarkEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.create-received-trust-mark"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/received-trust-marks",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createReceivedTrustMark",
            tags = setOf("received-trust-marks"),
            summary = "Create a new received trust mark"
        )
    }
}

// ==================== Delete Received Trust Mark Endpoint ====================

interface DeleteReceivedTrustMarkEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.delete-received-trust-mark"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/received-trust-marks/{id}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteReceivedTrustMark",
            tags = setOf("received-trust-marks"),
            summary = "Delete a received trust mark by ID"
        )
    }
}
