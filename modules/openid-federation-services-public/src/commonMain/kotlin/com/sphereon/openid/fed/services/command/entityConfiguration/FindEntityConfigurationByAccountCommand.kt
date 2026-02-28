package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement

data class FindEntityConfigurationByAccountArgs(
    val account: Account
)

interface FindEntityConfigurationByAccountCommand : ServiceCommand<FindEntityConfigurationByAccountArgs, EntityConfigurationStatement>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.entity-configuration.find-by-account"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/entity-statement",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "getEntityStatement",
            tags = setOf("entity-statement"),
            summary = "Get entity statement"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
