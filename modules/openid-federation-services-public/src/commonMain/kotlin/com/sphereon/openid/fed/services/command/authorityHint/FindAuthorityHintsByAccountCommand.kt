package com.sphereon.openid.fed.services.command.authorityHint

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.AuthorityHint

data class FindAuthorityHintsByAccountArgs(val tenantId: String)

interface FindAuthorityHintsByAccountCommand : ServiceCommand<FindAuthorityHintsByAccountArgs, List<AuthorityHint>>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.authority-hint.find-by-account"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/authority-hints",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "listAuthorityHints",
            tags = setOf("authority-hints"),
            summary = "List authority hints"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
