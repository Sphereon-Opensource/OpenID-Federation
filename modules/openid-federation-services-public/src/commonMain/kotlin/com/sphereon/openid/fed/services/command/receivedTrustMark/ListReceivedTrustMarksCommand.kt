package com.sphereon.openid.fed.services.command.receivedTrustMark

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.ReceivedTrustMark

data class ListReceivedTrustMarksArgs(
    val tenantId: String
)

interface ListReceivedTrustMarksCommand : ServiceCommand<ListReceivedTrustMarksArgs, Array<ReceivedTrustMark>, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.received-trust-mark.list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/received-trust-marks",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "listReceivedTrustMarks",
            tags = setOf("received-trust-marks"),
            summary = "List received trust marks"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
