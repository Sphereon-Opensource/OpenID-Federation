package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.models.TrustMarkIssuer

data class AddIssuerToTrustMarkTypeArgs(val account: Account, val trustMarkTypeId: String, val issuerIdentifier: String)

interface AddIssuerToTrustMarkTypeCommand : ServiceCommand<AddIssuerToTrustMarkTypeArgs, TrustMarkIssuer>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.add-issuer-to-type"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/trust-mark-types/{id}/issuers",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "addTrustMarkTypeIssuer",
            tags = setOf("trust-mark-types"),
            summary = "Add trust mark type issuer"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
