package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.Subordinate

data class FindSubordinatesByAccountArgs(val account: Account)

interface FindSubordinatesByAccountCommand : ServiceCommand<FindSubordinatesByAccountArgs, Array<Subordinate>>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.subordinate.find-by-account"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/subordinates",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "listSubordinates",
            tags = setOf("subordinates"),
            summary = "List subordinates"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
