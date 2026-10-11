package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.HistoricalKey
import kotlinx.serialization.Serializable

@Serializable
data class ListHistoricalKeysArgs(
    val tenantId: String,
)

/** Lists public historical keys without signing a response. */
interface ListHistoricalKeysCommand : ServiceCommand<ListHistoricalKeysArgs, List<HistoricalKey>, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.jwk.list-historical-keys"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/keys/historical",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "listHistoricalKeys",
            tags = setOf("keys"),
            summary = "List historical keys",
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
