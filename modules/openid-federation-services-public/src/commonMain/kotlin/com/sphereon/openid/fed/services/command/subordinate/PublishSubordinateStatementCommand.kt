package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand


data class PublishSubordinateStatementArgs(
    val tenantId: String,
    val id: String,
    val dryRun: Boolean? = false,
    val kmsKeyRef: String? = null,
    val kid: String? = null
)

interface PublishSubordinateStatementCommand : ServiceCommand<PublishSubordinateStatementArgs, String, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.subordinate.publish-statement"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/subordinates/{id}/statement",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "publishSubordinateStatement",
            tags = setOf("subordinates"),
            summary = "Publish subordinate statement"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
