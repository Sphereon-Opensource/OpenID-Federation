package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand


data class PublishEntityConfigurationArgs(
    val tenantId: String,
    val dryRun: Boolean? = false,
    val kmsKeyRef: String? = null,
    val kid: String? = null
)

interface PublishEntityConfigurationCommand : ServiceCommand<PublishEntityConfigurationArgs, String>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.entity-configuration.publish"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/entity-statement",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "publishEntityStatement",
            tags = setOf("entity-statement"),
            summary = "Publish entity statement"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
