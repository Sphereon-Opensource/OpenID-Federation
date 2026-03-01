package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.TrustMarkType

data class DeleteTrustMarkTypeArgs(val tenantId: String, val id: String)

interface DeleteTrustMarkTypeCommand : ServiceCommand<DeleteTrustMarkTypeArgs, TrustMarkType>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.delete-type"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/trust-mark-types/{id}",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "deleteTrustMarkType",
            tags = setOf("trust-mark-types"),
            summary = "Delete a trust mark type"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
