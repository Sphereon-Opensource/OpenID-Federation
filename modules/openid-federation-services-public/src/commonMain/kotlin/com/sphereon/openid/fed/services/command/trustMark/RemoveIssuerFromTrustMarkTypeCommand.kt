package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.persistence.models.TrustMarkIssuer

data class RemoveIssuerFromTrustMarkTypeArgs(val tenantId: String, val trustMarkTypeId: String, val issuerId: String)

interface RemoveIssuerFromTrustMarkTypeCommand : ServiceCommand<RemoveIssuerFromTrustMarkTypeArgs, TrustMarkIssuer, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.remove-issuer-from-type"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/trust-mark-types/{id}/issuers/{issuerId}",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "removeTrustMarkTypeIssuer",
            tags = setOf("trust-mark-types"),
            summary = "Remove trust mark type issuer"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
