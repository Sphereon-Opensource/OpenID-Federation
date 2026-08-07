package com.sphereon.openid.fed.services.command.criticalClaim

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.persistence.models.Crit as CritEntity

data class FindCriticalClaimsByAccountArgs(
    val tenantId: String
)

interface FindCriticalClaimsByAccountCommand : ServiceCommand<FindCriticalClaimsByAccountArgs, Array<CritEntity>, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.critical-claim.find-by-account"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/critical-claims",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "listCriticalClaims",
            tags = setOf("critical-claims"),
            summary = "List critical claims"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
