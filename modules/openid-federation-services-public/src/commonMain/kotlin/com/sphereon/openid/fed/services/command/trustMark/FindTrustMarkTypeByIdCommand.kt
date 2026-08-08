package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.TrustMarkType

data class FindTrustMarkTypeByIdArgs(val tenantId: String, val id: String)

interface FindTrustMarkTypeByIdCommand : ServiceCommand<FindTrustMarkTypeByIdArgs, TrustMarkType, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.find-type-by-id"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-mark-types/{id}",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "getTrustMarkType",
            tags = setOf("trust-mark-types"),
            summary = "Get trust mark type"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
