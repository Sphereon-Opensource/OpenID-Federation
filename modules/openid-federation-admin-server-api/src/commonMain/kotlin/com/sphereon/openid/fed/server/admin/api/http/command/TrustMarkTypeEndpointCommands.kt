package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

// ==================== List Trust Mark Types Endpoint ====================

interface ListTrustMarkTypesEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.list-trust-mark-types"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-mark-types",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listTrustMarkTypes",
            tags = setOf("trust-marks"),
            summary = "List all trust mark types for the current account"
        )
    }
}

// ==================== Create Trust Mark Type Endpoint ====================

interface CreateTrustMarkTypeEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.create-trust-mark-type"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/trust-mark-types",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createTrustMarkType",
            tags = setOf("trust-marks"),
            summary = "Create a new trust mark type"
        )
    }
}

// ==================== Get Trust Mark Type Endpoint ====================

interface GetTrustMarkTypeEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.get-trust-mark-type"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-mark-types/{id}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "getTrustMarkType",
            tags = setOf("trust-marks"),
            summary = "Get a trust mark type by ID"
        )
    }
}

// ==================== Delete Trust Mark Type Endpoint ====================

interface DeleteTrustMarkTypeEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.delete-trust-mark-type"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/trust-mark-types/{id}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteTrustMarkType",
            tags = setOf("trust-marks"),
            summary = "Delete a trust mark type by ID"
        )
    }
}

// ==================== Get Trust Mark Type Issuers Endpoint ====================

interface GetTrustMarkTypeIssuersEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.get-trust-mark-type-issuers"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-mark-types/{id}/issuers",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "getTrustMarkTypeIssuers",
            tags = setOf("trust-marks"),
            summary = "Get issuers for a trust mark type"
        )
    }
}

// ==================== Add Trust Mark Type Issuer Endpoint ====================

interface AddTrustMarkTypeIssuerEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.add-trust-mark-type-issuer"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/trust-mark-types/{id}/issuers",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "addTrustMarkTypeIssuer",
            tags = setOf("trust-marks"),
            summary = "Add an issuer to a trust mark type"
        )
    }
}

// ==================== Remove Trust Mark Type Issuer Endpoint ====================

interface RemoveTrustMarkTypeIssuerEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.remove-trust-mark-type-issuer"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/trust-mark-types/{id}/issuers/{issuerId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "removeTrustMarkTypeIssuer",
            tags = setOf("trust-marks"),
            summary = "Remove an issuer from a trust mark type by ID"
        )
    }
}
