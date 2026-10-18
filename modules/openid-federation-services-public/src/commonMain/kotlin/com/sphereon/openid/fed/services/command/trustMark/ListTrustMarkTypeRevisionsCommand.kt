package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.PublicApiCommand
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.TrustMarkTypeRevision

data class ListTrustMarkTypeRevisionsArgs(val tenantId: String, val id: String)

/** Lists the definition revisions of one of the account's Trust Mark types, oldest first, deleted types included. */
interface ListTrustMarkTypeRevisionsCommand :
    ServiceCommand<ListTrustMarkTypeRevisionsArgs, List<TrustMarkTypeRevision>, FederationError>, PublicApiCommand {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.list-type-revisions"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-mark-types/{id}/revisions",
            produces = setOf(MediaType.ApplicationJson),
            commandId = COMMAND_ID,
            operationId = "listTrustMarkTypeRevisions",
            tags = setOf("trust-mark-types"),
            summary = "List the definition revisions of a trust mark type"
        )
    }

    override val httpEndpoint get() = ENDPOINT
}
