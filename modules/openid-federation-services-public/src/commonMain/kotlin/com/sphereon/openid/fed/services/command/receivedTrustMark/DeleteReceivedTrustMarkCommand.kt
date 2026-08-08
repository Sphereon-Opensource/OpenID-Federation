package com.sphereon.openid.fed.services.command.receivedTrustMark

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.ReceivedTrustMark

data class DeleteReceivedTrustMarkArgs(
    val tenantId: String,
    val trustMarkId: String
)

interface DeleteReceivedTrustMarkCommand : ServiceCommand<DeleteReceivedTrustMarkArgs, ReceivedTrustMark, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.received-trust-mark.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/received-trust-marks/{id}",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "deleteReceivedTrustMark",
            tags = setOf("received-trust-marks"),
            summary = "Delete a received trust mark"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
