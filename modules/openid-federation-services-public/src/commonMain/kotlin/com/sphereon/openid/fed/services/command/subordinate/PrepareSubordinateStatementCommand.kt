package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Constraints
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Reviewed subordinate snapshot. Preparation signs but never persists subordinate or publication rows. */
@Serializable
data class PrepareSubordinateStatementArgs(
    val accountId: String,
    val subject: String,
    val publicJwks: List<JsonObject>,
    val metadata: JsonObject? = null,
    val constraints: Constraints? = null,
    val expectedSelectionRevision: Long? = null,
    val expectedMetadataPolicy: JsonObject? = null,
    val expectedMetadataPolicyRevisions: Map<String, Long>? = null,
)

/** Actual signing and account-policy provenance; no cross-store commit or later stability is implied. */
@Serializable
data class PreparedSubordinateStatement(
    val compact: String,
    val accountId: String,
    val issuer: String,
    val subject: String,
    val selectedKeyId: String,
    val kid: String,
    val algorithm: String,
    val selectionRevision: Long,
    val metadataPolicy: JsonObject,
    val metadataPolicyRevisions: Map<String, Long>,
)

/** Internal command; success is a prepared compact JWT, not admission or publication evidence. */
interface PrepareSubordinateStatementCommand : ServiceCommand<PrepareSubordinateStatementArgs, PreparedSubordinateStatement, FederationError> {
    companion object { const val COMMAND_ID = "fed.subordinate.prepare-statement" }
}
