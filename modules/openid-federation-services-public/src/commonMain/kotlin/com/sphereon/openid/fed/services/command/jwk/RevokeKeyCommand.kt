package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.AccountJwk

data class RevokeKeyArgs(
    val tenantId: String,
    val keyId: String,
    val reason: String?
)

interface RevokeKeyCommand : ServiceCommand<RevokeKeyArgs, AccountJwk>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.jwk.revoke-key"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/accounts/keys/{kid}/revoke",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "revokeKey",
            tags = setOf("keys"),
            summary = "Revoke a key"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
