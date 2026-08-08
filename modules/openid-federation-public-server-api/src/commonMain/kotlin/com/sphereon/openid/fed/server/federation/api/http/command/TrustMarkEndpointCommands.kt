package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

/**
 * Custom content type for trust marks (JWTs).
 */
val TrustMarkJwtMediaType = MediaType.Custom("application/trust-mark+jwt")

/**
 * Custom content type for trust mark status responses (signed JWTs).
 */
val TrustMarkStatusResponseJwtMediaType = MediaType.Custom("application/trust-mark-status-response+jwt")

// ==================== Trust Mark Status GET (Root) Endpoint ====================

interface GetTrustMarkStatusRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.get-trust-mark-status"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-mark-status",
            produces = setOf(TrustMarkStatusResponseJwtMediaType),
            operationId = "checkTrustMarkStatusGet",
            tags = setOf("trust-marks"),
            summary = "Check trust mark status (GET)"
        )
    }
}

// ==================== Trust Mark Status POST (Root) Endpoint ====================

interface TrustMarkStatusRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.trust-mark-status"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/trust-mark-status",
            consumes = setOf(MediaType.ApplicationFormUrlEncoded),
            produces = setOf(TrustMarkStatusResponseJwtMediaType),
            operationId = "checkTrustMarkStatus",
            tags = setOf("trust-marks"),
            summary = "Check trust mark status"
        )
    }
}

// ==================== Trust Mark Status GET (Per Account) Endpoint ====================

interface GetTrustMarkStatusAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.get-account-trust-mark-status"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{username}/trust-mark-status",
            produces = setOf(TrustMarkStatusResponseJwtMediaType),
            operationId = "checkAccountTrustMarkStatusGet",
            tags = setOf("trust-marks"),
            summary = "Check trust mark status for a specific account (GET)"
        )
    }
}

// ==================== Trust Mark Status POST (Per Account) Endpoint ====================

interface TrustMarkStatusAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account-trust-mark-status"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/{username}/trust-mark-status",
            consumes = setOf(MediaType.ApplicationFormUrlEncoded),
            produces = setOf(TrustMarkStatusResponseJwtMediaType),
            operationId = "checkAccountTrustMarkStatus",
            tags = setOf("trust-marks"),
            summary = "Check trust mark status for a specific account"
        )
    }
}

// ==================== Trust Mark List GET (Root) Endpoint ====================

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

// ==================== Trust Mark List POST (Root) Endpoint ====================

interface PostTrustMarkListRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.post-trust-mark-list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/trust-mark-list",
            consumes = setOf(MediaType.ApplicationFormUrlEncoded),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listTrustMarkedSubordinatesPost",
            tags = setOf("trust-marks"),
            summary = "List subordinates with a specific trust mark (POST)"
        )
    }
}

// ==================== Trust Mark List GET (Per Account) Endpoint ====================

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

// ==================== Trust Mark List POST (Per Account) Endpoint ====================

interface PostTrustMarkListAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.post-account-trust-mark-list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/{username}/trust-mark-list",
            consumes = setOf(MediaType.ApplicationFormUrlEncoded),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listAccountTrustMarkedSubordinatesPost",
            tags = setOf("trust-marks"),
            summary = "List subordinates with a specific trust mark for an account (POST)"
        )
    }
}

// ==================== Get Trust Mark GET (Root) Endpoint ====================

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

// ==================== Get Trust Mark POST (Root) Endpoint ====================

interface PostGetTrustMarkRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.post-trust-mark"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/trust-mark",
            consumes = setOf(MediaType.ApplicationFormUrlEncoded),
            produces = setOf(TrustMarkJwtMediaType),
            operationId = "getTrustMarkPost",
            tags = setOf("trust-marks"),
            summary = "Get a trust mark JWT (POST)"
        )
    }
}

// ==================== Get Trust Mark GET (Per Account) Endpoint ====================

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

// ==================== Get Trust Mark POST (Per Account) Endpoint ====================

interface PostGetTrustMarkAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.post-account-trust-mark"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/{username}/trust-mark",
            consumes = setOf(MediaType.ApplicationFormUrlEncoded),
            produces = setOf(TrustMarkJwtMediaType),
            operationId = "getAccountTrustMarkPost",
            tags = setOf("trust-marks"),
            summary = "Get a trust mark JWT for a specific account (POST)"
        )
    }
}
