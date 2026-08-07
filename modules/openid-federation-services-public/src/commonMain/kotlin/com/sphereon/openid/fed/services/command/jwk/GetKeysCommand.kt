package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.TenantJwk

data class GetKeysArgs(
    val tenantId: String,
    val includeRevoked: Boolean = false
)

interface GetKeysCommand : ServiceCommand<GetKeysArgs, Array<TenantJwk>, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.jwk.get-keys"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/accounts/keys",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "listKeys",
            tags = setOf("keys"),
            summary = "List keys"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
