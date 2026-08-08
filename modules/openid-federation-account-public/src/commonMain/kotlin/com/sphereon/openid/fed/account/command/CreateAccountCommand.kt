package com.sphereon.openid.fed.account.command

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateAccount

interface CreateAccountCommand : ServiceCommand<CreateAccount, Account, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.account.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/accounts",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "createAccount",
            tags = setOf("accounts"),
            summary = "Create a new account"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
