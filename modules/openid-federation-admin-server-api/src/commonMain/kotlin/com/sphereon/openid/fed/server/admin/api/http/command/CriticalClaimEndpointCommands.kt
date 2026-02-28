package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.openid.fed.persistence.models.Crit as CritEntity
import kotlinx.serialization.Serializable

/**
 * Response model for critical claim operations.
 */
@Serializable
data class CritResponse(
    val id: String,
    val accountId: String,
    val claim: String
)

/**
 * Extension function to convert a CritEntity to a CritResponse.
 */
fun CritEntity.toCritResponse(): CritResponse = CritResponse(
    id = id,
    accountId = account_id,
    claim = claim
)

// ==================== List Critical Claims Endpoint ====================

interface ListCriticalClaimsEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.list-critical-claims"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/crits",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listCriticalClaims",
            tags = setOf("critical-claims"),
            summary = "List all critical claims for the current account"
        )
    }
}

// ==================== Create Critical Claim Endpoint ====================

interface CreateCriticalClaimEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.create-critical-claim"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/crits",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createCriticalClaim",
            tags = setOf("critical-claims"),
            summary = "Create a new critical claim"
        )
    }
}

// ==================== Delete Critical Claim Endpoint ====================

interface DeleteCriticalClaimEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.delete-critical-claim"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/crits/{id}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteCriticalClaim",
            tags = setOf("critical-claims"),
            summary = "Delete a critical claim by ID"
        )
    }
}
