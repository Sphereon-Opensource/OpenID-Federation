package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.TrustMarkType

data class FindAllTrustMarkTypesByAccountArgs(val tenantId: String)

interface FindAllTrustMarkTypesByAccountCommand : ServiceCommand<FindAllTrustMarkTypesByAccountArgs, List<TrustMarkType>>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.find-all-types-by-account"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-mark-types",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "listTrustMarkTypes",
            tags = setOf("trust-mark-types"),
            summary = "List trust mark types"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
