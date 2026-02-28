package com.sphereon.openid.fed.services.command.criticalClaim

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.models.Crit as CritEntity

data class CreateCriticalClaimArgs(
    val account: Account,
    val claim: String
)

interface CreateCriticalClaimCommand : ServiceCommand<CreateCriticalClaimArgs, CritEntity>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.critical-claim.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/critical-claims",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "createCriticalClaim",
            tags = setOf("critical-claims"),
            summary = "Create a critical claim"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
