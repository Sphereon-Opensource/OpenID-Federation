package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.AccountJwk
import com.sphereon.openid.fed.services.CreateKeyArgs

data class CreateKeyCommandArgs(
    val tenantId: String,
    val opts: CreateKeyArgs = CreateKeyArgs()
)

interface CreateKeyCommand : ServiceCommand<CreateKeyCommandArgs, AccountJwk>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.jwk.create-key"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/accounts/keys",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "createKey",
            tags = setOf("keys"),
            summary = "Create a key"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
