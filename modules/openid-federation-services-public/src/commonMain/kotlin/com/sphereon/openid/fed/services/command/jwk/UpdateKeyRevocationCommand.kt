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
data class UpdateKeyRevocationArgs(
    val tenantId: String,
    val keyId: String,
    val reason: String,
    val revokedAt: Long? = null,
)

/** Updates the reason and earliest revocation time of a historical key. */
interface UpdateKeyRevocationCommand : ServiceCommand<UpdateKeyRevocationArgs, TenantJwk, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.jwk.update-key-revocation"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/keys/{keyId}/revocation",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "updateKeyRevocation",
            tags = setOf("keys"),
            summary = "Update a historical key revocation",
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
