package com.sphereon.openid.fed.account.command

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account

interface GetAllAccountsCommand : ServiceCommand<Unit, List<Account>, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.account.get-all"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/accounts",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "listAccounts",
            tags = setOf("accounts"),
            summary = "List all accounts"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
