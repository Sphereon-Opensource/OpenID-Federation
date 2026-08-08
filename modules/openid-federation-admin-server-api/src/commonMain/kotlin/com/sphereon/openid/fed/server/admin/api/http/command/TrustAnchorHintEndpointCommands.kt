package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

// ==================== List Trust Anchor Hints Endpoint ====================

interface ListTrustAnchorHintsEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.list-trust-anchor-hints"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-anchor-hints",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listTrustAnchorHints",
            tags = setOf("trust-anchor-hints"),
            summary = "List all trust anchor hints for the current account"
        )
    }
}

// ==================== Create Trust Anchor Hint Endpoint ====================

interface CreateTrustAnchorHintEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.create-trust-anchor-hint"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/trust-anchor-hints",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createTrustAnchorHint",
            tags = setOf("trust-anchor-hints"),
            summary = "Create a new trust anchor hint"
        )
    }
}

// ==================== Delete Trust Anchor Hint Endpoint ====================

interface DeleteTrustAnchorHintEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.delete-trust-anchor-hint"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/trust-anchor-hints/{id}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteTrustAnchorHint",
            tags = setOf("trust-anchor-hints"),
            summary = "Delete a trust anchor hint by ID"
        )
    }
}
