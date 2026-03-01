package com.sphereon.openid.fed.account.command

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account

data class DeleteAccountArgs(val account: Account)

interface DeleteAccountCommand : ServiceCommand<DeleteAccountArgs, Account>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.account.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/accounts",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "deleteAccount",
            tags = setOf("accounts"),
            summary = "Delete the current account"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
