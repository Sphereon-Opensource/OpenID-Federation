package com.sphereon.openid.fed.client.command.discovery

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError

/**
 * Arguments for listing Immediate Subordinates of a superior (OIDFed 1.1 §8.2).
 */
data class ListSubordinatesArgs(
    /** Entity Identifier of the superior that exposes `federation_list_endpoint`. */
    val superiorEntityId: String,
    /** Optional `entity_type` query filter (e.g. `openid_credential_issuer`). */
    val entityType: String? = null,
    val trustMarked: Boolean? = null,
    val trustMarkType: String? = null,
    /** When true, only Intermediate subordinates; when false, only leaves. */
    val intermediate: Boolean? = null,
)

/**
 * Immediate subordinates returned by a federation list endpoint (JSON array of Entity Identifiers).
 */
data class ListSubordinatesResult(
    val superiorEntityId: String,
    val listEndpoint: String,
    val entityIdentifiers: List<String>,
)

interface ListSubordinatesCommandService {
    suspend fun listSubordinates(
        superiorEntityId: String,
        entityType: String? = null,
        trustMarked: Boolean? = null,
        trustMarkType: String? = null,
        intermediate: Boolean? = null,
    ): IdkResult<ListSubordinatesResult, FederationError>
}

/**
 * Call a superior's `federation_list_endpoint` (OIDFed 1.1 §8.2).
 */
interface ListSubordinatesCommand :
    Command<ListSubordinatesArgs, ListSubordinatesResult, FederationError>,
    ListSubordinatesCommandService {

    companion object {
        const val COMMAND_ID = "fed.client.list-subordinates"
    }
}
