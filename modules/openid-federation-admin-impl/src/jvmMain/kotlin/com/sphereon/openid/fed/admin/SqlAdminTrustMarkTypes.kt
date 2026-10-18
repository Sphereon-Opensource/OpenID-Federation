package com.sphereon.openid.fed.admin

import com.sphereon.openid.fed.openapi.models.TrustMarkTypeDefinition
import com.sphereon.openid.fed.persistence.models.AdminDocument
import com.sphereon.openid.fed.persistence.models.AdminDocumentQueries
import com.sphereon.openid.fed.persistence.models.TrustMarkTypeQueries
import com.sphereon.openid.fed.services.command.trustMark.TrustMarkTypeDefinitions
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

/** TrustMarkType owns issuance defaults. Administration stores only its extra document members and revision. */
internal class SqlAdminTrustMarkTypes(private val types: TrustMarkTypeQueries, private val documents: AdminDocumentQueries) {
    private val mapped = mapOf("ref" to "ref", "logo_uri" to "logoUri", "lifetime" to "lifetimeSeconds")
    private val owned = mapped.keys + setOf("trust_mark_type", "delegation", "status")
    private fun binding(account: String, type: String) = documents.find(account, "trust-mark-type-binding", type, "").executeAsOneOrNull()
    private fun boundId(account: String, type: String) = binding(account, type)?.let { Json.parseToJsonElement(it.document).jsonObject.string("id") }

    fun synchronize(account: String) {
        types.findAllByAccountIdIncludingDeleted(account).executeAsList().distinctBy { it.identifier }.forEach { type ->
            if (documents.find(account, "trust-mark-type", type.identifier, "").executeAsOneOrNull() == null) {
                documents.upsert(account, "trust-mark-type", type.identifier, "", "{}", "\"${UUID.randomUUID()}\"", now(), null)
                bind(account, type.identifier, type.id)
            }
        }
    }

    fun lock(account: String, keys: Collection<AdminDocumentKey>) {
        keys.filter { it.collection == "trust-mark-type" }.map { it.resourceId }.distinct().sorted().forEach {
            types.findLatestByAccountAndIdentifierForUpdate(account, it).executeAsOneOrNull()
        }
    }

    fun project(row: AdminDocument): AdminStoredDocument {
        val type = types.findLatestByAccountAndIdentifier(row.account_id, row.resource_id).executeAsOneOrNull()
        val extensions = Json.parseToJsonElement(row.document).jsonObject
        val definition = type?.definition?.let { Json.parseToJsonElement(it).jsonObject }.orEmpty()
        val document = buildJsonObject {
            if (type != null && boundId(row.account_id, row.resource_id) == type.id)
                extensions.filterKeys { it !in owned }.forEach { (key, value) -> put(key, value) }
            put("trust_mark_type", row.resource_id)
            mapped.forEach { (standard, core) -> definition[core]?.takeUnless { it is JsonNull }?.let { put(standard, it) } }
            type?.delegation?.let { put("delegation", it) }
            if (type == null || type.deleted_at != null) put("status", "revoked")
        }
        val revision = listOf(row.etag, type?.id, type?.version, type?.delegation, type?.deleted_at).joinToString("|")
        val etag = "\"" + MessageDigest.getInstance("SHA-256").digest(revision.toByteArray()).joinToString("") { "%02x".format(it) } + "\""
        return AdminStoredDocument(AdminDocumentKey(row.collection, row.resource_id, row.subject_id), document, etag, row.updated_at, row.artifact)
    }

    /** Called under the account and type row locks, in the same transaction as the administration revision. */
    fun write(account: String, mutation: AdminMutation): JsonObject {
        val identifier = mutation.key.resourceId
        val current = types.findLatestByAccountAndIdentifier(account, identifier).executeAsOneOrNull()
        if (mutation.document.string("status") == "revoked") {
            if (current != null && current.deleted_at == null) types.delete(current.id).executeAsOne()
        } else {
            val previousDefinition = current?.definition?.let { Json.parseToJsonElement(it).jsonObject }.orEmpty()
            val definition = buildJsonObject {
                previousDefinition.filterKeys { it !in mapped.values }.forEach { (key, value) -> put(key, value) }
                if ("name" !in previousDefinition) put("name", identifier.take(TrustMarkTypeDefinitions.NAME_MAX_LENGTH))
                mapped.forEach { (standard, core) -> mutation.document[standard]?.let { put(core, it) } }
            }
            val typed = try { Json { ignoreUnknownKeys = true }.decodeFromJsonElement(TrustMarkTypeDefinition.serializer(), definition) }
                catch (_: Exception) { adminFail("invalid_request", 400, "Invalid Trust Mark issuance defaults") }
            val checked = TrustMarkTypeDefinitions.validate(typed)
            if (checked.isErr) adminFail("invalid_federation_claim", 400, "Invalid Trust Mark issuance defaults")
            val encoded = TrustMarkTypeDefinitions.encode(checked.value)
            val type = if (current == null || current.deleted_at != null) {
                types.createWithDefinition(identifier, account, encoded).executeAsOne().also {
                    types.insertRevision(it.id, it.version, it.definition).executeAsOne()
                }
            } else if (current.definition != encoded) {
                (types.updateDefinition(encoded, account, current.id, current.version).executeAsOneOrNull()
                    ?: adminFail("precondition_failed", 412, "Trust Mark definition changed")).also {
                    types.insertRevision(it.id, it.version, it.definition).executeAsOne()
                }
            } else current
            val delegation = mutation.document.string("delegation")
            if (type.delegation != delegation) types.setDelegation(delegation, account, type.id).executeAsOne()
            bind(account, identifier, type.id)
        }
        return JsonObject(mutation.document.filterKeys { it !in owned })
    }

    private fun bind(account: String, identifier: String, id: String) {
        documents.upsert(account, "trust-mark-type-binding", identifier, "", buildJsonObject { put("id", id) }.toString(),
            "\"${UUID.randomUUID()}\"", now(), null)
    }
    private fun now() = System.currentTimeMillis() / 1000
}
