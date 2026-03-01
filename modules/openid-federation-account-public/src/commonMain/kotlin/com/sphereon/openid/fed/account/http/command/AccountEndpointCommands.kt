package com.sphereon.openid.fed.account.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

// ==================== List Accounts Endpoint ====================

interface ListAccountsEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.list-accounts"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/accounts",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listAccounts",
            tags = setOf("accounts"),
            summary = "List all accounts"
        )
    }
}

// ==================== Create Account Endpoint ====================

interface CreateAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.create-account"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/accounts",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createAccount",
            tags = setOf("accounts"),
            summary = "Create a new account"
        )
    }
}

// ==================== Delete Account Endpoint ====================

interface DeleteAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.delete-account"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/accounts",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteAccount",
            tags = setOf("accounts"),
            summary = "Delete the current account"
        )
    }
}
