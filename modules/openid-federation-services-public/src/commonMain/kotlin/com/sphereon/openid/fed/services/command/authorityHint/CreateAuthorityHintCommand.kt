package com.sphereon.openid.fed.services.command.authorityHint

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.AuthorityHint

data class CreateAuthorityHintArgs(val tenantId: String, val identifier: String)

interface CreateAuthorityHintCommand : ServiceCommand<CreateAuthorityHintArgs, AuthorityHint>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.authority-hint.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/authority-hints",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "createAuthorityHint",
            tags = setOf("authority-hints"),
            summary = "Create an authority hint"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
