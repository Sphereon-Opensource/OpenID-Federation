package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.SubordinateStatement

data class GetSubordinateStatementArgs(val account: Account, val id: String)

interface GetSubordinateStatementCommand : ServiceCommand<GetSubordinateStatementArgs, SubordinateStatement>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.subordinate.get-statement"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/subordinates/{id}/statement",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "getSubordinateStatement",
            tags = setOf("subordinates"),
            summary = "Get subordinate statement"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
