package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.persistence.models.TrustMark as TrustMarkEntity

data class DeleteTrustMarkArgs(val tenantId: String, val id: String)

interface DeleteTrustMarkCommand : ServiceCommand<DeleteTrustMarkArgs, TrustMarkEntity, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/trust-marks/{id}",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "deleteTrustMark",
            tags = setOf("trust-marks"),
            summary = "Delete a trust mark"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
