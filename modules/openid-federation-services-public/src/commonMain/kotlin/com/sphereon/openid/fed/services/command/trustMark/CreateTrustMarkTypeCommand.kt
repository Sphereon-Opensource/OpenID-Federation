package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.openid.fed.openapi.models.TrustMarkType

data class CreateTrustMarkTypeArgs(val tenantId: String, val createDto: CreateTrustMarkType)

interface CreateTrustMarkTypeCommand : ServiceCommand<CreateTrustMarkTypeArgs, TrustMarkType>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.create-type"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/trust-mark-types",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "createTrustMarkType",
            tags = setOf("trust-mark-types"),
            summary = "Create a trust mark type"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
