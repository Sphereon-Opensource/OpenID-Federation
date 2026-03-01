package com.sphereon.openid.fed.services.command.trustAnchorHint

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.TrustAnchorHint

data class CreateTrustAnchorHintArgs(val tenantId: String, val identifier: String)

interface CreateTrustAnchorHintCommand : ServiceCommand<CreateTrustAnchorHintArgs, TrustAnchorHint>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.trust-anchor-hint.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/trust-anchor-hints",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "createTrustAnchorHint",
            tags = setOf("trust-anchor-hints"),
            summary = "Create a trust anchor hint"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
