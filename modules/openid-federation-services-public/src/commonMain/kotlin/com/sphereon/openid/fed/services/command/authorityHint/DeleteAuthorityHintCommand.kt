package com.sphereon.openid.fed.services.command.authorityHint

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AuthorityHint

data class DeleteAuthorityHintArgs(val account: Account, val id: String)

interface DeleteAuthorityHintCommand : ServiceCommand<DeleteAuthorityHintArgs, AuthorityHint>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.authority-hint.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/authority-hints/{id}",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "deleteAuthorityHint",
            tags = setOf("authority-hints"),
            summary = "Delete an authority hint"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
