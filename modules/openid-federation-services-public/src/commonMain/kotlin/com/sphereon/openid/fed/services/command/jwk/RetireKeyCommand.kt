package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.TenantJwk
import kotlinx.serialization.Serializable

@Serializable
data class RetireKeyArgs(
    val tenantId: String,
    val keyId: String,
)

/** Retires an active key after its signing selection has changed. */
interface RetireKeyCommand : ServiceCommand<RetireKeyArgs, TenantJwk, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.jwk.retire-key"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/keys/{keyId}/retire",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "retireKey",
            tags = setOf("keys"),
            summary = "Retire an active key",
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
