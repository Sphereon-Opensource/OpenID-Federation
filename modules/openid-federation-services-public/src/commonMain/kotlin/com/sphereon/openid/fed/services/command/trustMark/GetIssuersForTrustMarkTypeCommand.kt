package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.persistence.models.TrustMarkIssuer

data class GetIssuersForTrustMarkTypeArgs(val tenantId: String, val trustMarkTypeId: String)

interface GetIssuersForTrustMarkTypeCommand : ServiceCommand<GetIssuersForTrustMarkTypeArgs, Array<TrustMarkIssuer>, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.get-issuers-for-type"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-mark-types/{id}/issuers",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "getTrustMarkTypeIssuers",
            tags = setOf("trust-mark-types"),
            summary = "Get trust mark type issuers"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
