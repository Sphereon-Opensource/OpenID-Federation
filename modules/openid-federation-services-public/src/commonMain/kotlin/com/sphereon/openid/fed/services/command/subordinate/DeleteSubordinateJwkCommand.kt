package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.SubordinateJwk

data class DeleteSubordinateJwkArgs(val account: Account, val id: String, val jwkId: String)

interface DeleteSubordinateJwkCommand : ServiceCommand<DeleteSubordinateJwkArgs, SubordinateJwk>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.subordinate.delete-jwk"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/subordinates/{id}/keys/{kid}",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "deleteSubordinateKey",
            tags = setOf("subordinates"),
            summary = "Delete a subordinate key"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
