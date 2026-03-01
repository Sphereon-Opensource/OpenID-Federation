package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.TrustMark

data class GetTrustMarksForAccountArgs(val tenantId: String)

interface GetTrustMarksForAccountCommand : ServiceCommand<GetTrustMarksForAccountArgs, List<TrustMark>>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.get-for-account"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-marks",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "listTrustMarks",
            tags = setOf("trust-marks"),
            summary = "List trust marks"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
