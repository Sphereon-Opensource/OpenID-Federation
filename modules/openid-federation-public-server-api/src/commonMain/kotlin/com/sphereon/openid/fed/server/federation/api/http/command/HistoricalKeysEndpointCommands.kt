package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

/**
 * Custom content type for JWK Set JWTs.
 */
val JwkSetJwtMediaType = MediaType.Custom("application/jwk-set+jwt")

// ==================== Historical Keys (Root) Endpoint ====================

interface HistoricalKeysRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.historical-keys"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/historical-keys",
            produces = setOf(JwkSetJwtMediaType),
            operationId = "getHistoricalKeys",
            tags = setOf("keys"),
            summary = "Get historical federation keys"
        )
    }
}

// ==================== Historical Keys (Per Account) Endpoint ====================

interface HistoricalKeysAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account-historical-keys"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{username}/historical-keys",
            produces = setOf(JwkSetJwtMediaType),
            operationId = "getAccountHistoricalKeys",
            tags = setOf("keys"),
            summary = "Get historical federation keys for a specific account"
        )
    }
}
