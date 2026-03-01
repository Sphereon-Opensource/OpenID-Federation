package com.sphereon.openid.fed.services.command.receivedTrustMark

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.openid.fed.openapi.models.ReceivedTrustMark

data class CreateReceivedTrustMarkArgs(
    val tenantId: String,
    val createRequest: CreateReceivedTrustMark
)

interface CreateReceivedTrustMarkCommand : ServiceCommand<CreateReceivedTrustMarkArgs, ReceivedTrustMark>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.received-trust-mark.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/received-trust-marks",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "createReceivedTrustMark",
            tags = setOf("received-trust-marks"),
            summary = "Create a received trust mark"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
