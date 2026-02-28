package com.sphereon.openid.fed.services.command.criticalClaim

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.models.Crit as CritEntity

data class DeleteCriticalClaimArgs(
    val account: Account,
    val id: String
)

interface DeleteCriticalClaimCommand : ServiceCommand<DeleteCriticalClaimArgs, CritEntity>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.critical-claim.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/critical-claims/{id}",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "deleteCriticalClaim",
            tags = setOf("critical-claims"),
            summary = "Delete a critical claim"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
