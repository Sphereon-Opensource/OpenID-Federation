package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

/**
 * Custom content type for entity statements (JWTs).
 */
val EntityStatementJwtMediaType = MediaType.Custom("application/entity-statement+jwt")

// ==================== Entity Configuration (Root) Endpoint ====================

interface GetEntityConfigurationEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.entity-configuration"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/.well-known/openid-federation",
            produces = setOf(EntityStatementJwtMediaType),
            operationId = "getEntityConfiguration",
            tags = setOf("federation"),
            summary = "Get Entity Configuration Statement"
        )
    }
}

// ==================== Entity Configuration (Per Account) Endpoint ====================

interface GetAccountEntityConfigurationEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account-entity-configuration"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{username}/.well-known/openid-federation",
            produces = setOf(EntityStatementJwtMediaType),
            operationId = "getAccountEntityConfiguration",
            tags = setOf("federation"),
            summary = "Get Entity Configuration Statement for a specific account"
        )
    }
}
