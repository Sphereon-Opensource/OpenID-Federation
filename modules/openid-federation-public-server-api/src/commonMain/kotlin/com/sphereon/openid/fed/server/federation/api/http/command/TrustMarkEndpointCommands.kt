package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

/**
 * Custom content type for trust marks (JWTs).
 */
val TrustMarkJwtMediaType = MediaType.Custom("application/trust-mark+jwt")

// ==================== Trust Mark Status (Root) Endpoint ====================

interface TrustMarkStatusRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.trust-mark-status"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/trust-mark-status",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "checkTrustMarkStatus",
            tags = setOf("trust-marks"),
            summary = "Check trust mark status"
        )
    }
}

// ==================== Trust Mark Status (Per Account) Endpoint ====================

interface TrustMarkStatusAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account-trust-mark-status"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/{username}/trust-mark-status",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "checkAccountTrustMarkStatus",
            tags = setOf("trust-marks"),
            summary = "Check trust mark status for a specific account"
        )
    }
}

// ==================== Trust Mark List (Root) Endpoint ====================

interface TrustMarkListRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.trust-mark-list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-mark-list",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listTrustMarkedSubordinates",
            tags = setOf("trust-marks"),
            summary = "List subordinates with a specific trust mark"
        )
    }
}

// ==================== Trust Mark List (Per Account) Endpoint ====================

interface TrustMarkListAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account-trust-mark-list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{username}/trust-mark-list",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listAccountTrustMarkedSubordinates",
            tags = setOf("trust-marks"),
            summary = "List subordinates with a specific trust mark for an account"
        )
    }
}

// ==================== Get Trust Mark (Root) Endpoint ====================

interface GetTrustMarkRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.trust-mark"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-mark",
            produces = setOf(TrustMarkJwtMediaType),
            operationId = "getTrustMark",
            tags = setOf("trust-marks"),
            summary = "Get a trust mark JWT"
        )
    }
}

// ==================== Get Trust Mark (Per Account) Endpoint ====================

interface GetTrustMarkAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account-trust-mark"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{username}/trust-mark",
            produces = setOf(TrustMarkJwtMediaType),
            operationId = "getAccountTrustMark",
            tags = setOf("trust-marks"),
            summary = "Get a trust mark JWT for a specific account"
        )
    }
}
