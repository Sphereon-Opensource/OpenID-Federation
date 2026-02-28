package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

/**
 * Custom content type for resolve response JWTs.
 */
val ResolveResponseJwtMediaType = MediaType.Custom("application/resolve-response+jwt")

// ==================== Resolve Trust Chain (Root) Endpoint ====================

interface ResolveRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.resolve"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/resolve",
            produces = setOf(ResolveResponseJwtMediaType),
            operationId = "resolveTrustChain",
            tags = setOf("resolution"),
            summary = "Resolve trust chain for an entity"
        )
    }
}

// ==================== Resolve Trust Chain (Per Account) Endpoint ====================

interface ResolveAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account-resolve"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{username}/resolve",
            produces = setOf(ResolveResponseJwtMediaType),
            operationId = "resolveAccountTrustChain",
            tags = setOf("resolution"),
            summary = "Resolve trust chain for an entity within a specific account context"
        )
    }
}
