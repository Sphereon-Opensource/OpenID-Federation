package com.sphereon.openid.fed.services.command.trustAnchorHint

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.TrustAnchorHint

data class FindTrustAnchorHintsByAccountArgs(val tenantId: String)

interface FindTrustAnchorHintsByAccountCommand : ServiceCommand<FindTrustAnchorHintsByAccountArgs, List<TrustAnchorHint>, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.trust-anchor-hint.find-by-account"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-anchor-hints",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "listTrustAnchorHints",
            tags = setOf("trust-anchor-hints"),
            summary = "List trust anchor hints"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
