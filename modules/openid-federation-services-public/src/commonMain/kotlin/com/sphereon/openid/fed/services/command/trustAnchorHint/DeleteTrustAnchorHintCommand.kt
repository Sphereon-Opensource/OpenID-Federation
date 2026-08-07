package com.sphereon.openid.fed.services.command.trustAnchorHint

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.TrustAnchorHint

data class DeleteTrustAnchorHintArgs(val tenantId: String, val id: String)

interface DeleteTrustAnchorHintCommand : ServiceCommand<DeleteTrustAnchorHintArgs, TrustAnchorHint, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.trust-anchor-hint.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/trust-anchor-hints/{id}",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "deleteTrustAnchorHint",
            tags = setOf("trust-anchor-hints"),
            summary = "Delete a trust anchor hint"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
