package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.TrustMarkType
import com.sphereon.openid.fed.openapi.models.TrustMarkTypeDefinition

/**
 * Replaces the definition of the account's Trust Mark type [id] when its current version is [expectedVersion].
 */
data class UpdateTrustMarkTypeDefinitionArgs(
    val tenantId: String,
    val id: String,
    val expectedVersion: Long,
    val definition: TrustMarkTypeDefinition,
)

/**
 * Compare-and-set update of a Trust Mark type definition. A successful update increments the version by one and
 * records a revision; the identifier, owner and delegation are not changed. A stale [UpdateTrustMarkTypeDefinitionArgs.expectedVersion]
 * fails with `TrustMarkTypeVersionConflictError`.
 */
interface UpdateTrustMarkTypeDefinitionCommand :
    ServiceCommand<UpdateTrustMarkTypeDefinitionArgs, TrustMarkType, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.update-type-definition"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.PUT,
            pathPattern = "/trust-mark-types/{id}",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "updateTrustMarkTypeDefinition",
            tags = setOf("trust-mark-types"),
            summary = "Update the definition of a trust mark type"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
