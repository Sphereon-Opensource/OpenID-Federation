package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

// ==================== List Subordinates Endpoint ====================

interface ListSubordinatesEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.list-subordinates"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/subordinates",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listSubordinates",
            tags = setOf("subordinates"),
            summary = "List all subordinates for the current account"
        )
    }
}

// ==================== Create Subordinate Endpoint ====================

interface CreateSubordinateEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.create-subordinate"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/subordinates",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createSubordinate",
            tags = setOf("subordinates"),
            summary = "Create a new subordinate"
        )
    }
}

// ==================== Delete Subordinate Endpoint ====================

interface DeleteSubordinateEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.delete-subordinate"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/subordinates/{subordinateId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteSubordinate",
            tags = setOf("subordinates"),
            summary = "Delete a subordinate by ID"
        )
    }
}

// ==================== List Subordinate Keys Endpoint ====================

interface ListSubordinateKeysEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.list-subordinate-keys"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/subordinates/{subordinateId}/jwks",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listSubordinateKeys",
            tags = setOf("subordinates", "keys"),
            summary = "List keys for a subordinate"
        )
    }
}

// ==================== Create Subordinate Key Endpoint ====================

interface CreateSubordinateKeyEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.create-subordinate-key"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/subordinates/{subordinateId}/jwks",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createSubordinateKey",
            tags = setOf("subordinates", "keys"),
            summary = "Add a key to a subordinate"
        )
    }
}

// ==================== Delete Subordinate Key Endpoint ====================

interface DeleteSubordinateKeyEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.delete-subordinate-key"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/subordinates/{subordinateId}/jwks/{jwkId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteSubordinateKey",
            tags = setOf("subordinates", "keys"),
            summary = "Remove a key from a subordinate"
        )
    }
}

// ==================== Get Subordinate Statement Endpoint ====================

interface GetSubordinateStatementEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.get-subordinate-statement"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/subordinates/{subordinateId}/statement",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "getSubordinateStatement",
            tags = setOf("subordinates", "statements"),
            summary = "Get the statement for a subordinate"
        )
    }
}

// ==================== Publish Subordinate Statement Endpoint ====================

interface PublishSubordinateStatementEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.publish-subordinate-statement"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/subordinates/{subordinateId}/statement",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "publishSubordinateStatement",
            tags = setOf("subordinates", "statements"),
            summary = "Publish a subordinate statement"
        )
    }
}

// ==================== List Subordinate Metadata Endpoint ====================

interface ListSubordinateMetadataEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.list-subordinate-metadata"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/subordinates/{subordinateId}/metadata",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listSubordinateMetadata",
            tags = setOf("subordinates", "metadata"),
            summary = "List metadata for a subordinate"
        )
    }
}

// ==================== Create Subordinate Metadata Endpoint ====================

interface CreateSubordinateMetadataEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.create-subordinate-metadata"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/subordinates/{subordinateId}/metadata",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createSubordinateMetadata",
            tags = setOf("subordinates", "metadata"),
            summary = "Add metadata to a subordinate"
        )
    }
}

// ==================== Delete Subordinate Metadata Endpoint ====================

interface DeleteSubordinateMetadataEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.delete-subordinate-metadata"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/subordinates/{subordinateId}/metadata/{metadataId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteSubordinateMetadata",
            tags = setOf("subordinates", "metadata"),
            summary = "Remove metadata from a subordinate"
        )
    }
}
