package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.CreateTrustMarkRequest
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkResult

data class CreateTrustMarkArgs(
    val tenantId: String,
    val body: CreateTrustMarkRequest,
    val currentTimeMillis: Long = System.currentTimeMillis()
)

interface CreateTrustMarkCommand : ServiceCommand<CreateTrustMarkArgs, CreateTrustMarkResult>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/trust-marks",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "createTrustMark",
            tags = setOf("trust-marks"),
            summary = "Create a trust mark"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
