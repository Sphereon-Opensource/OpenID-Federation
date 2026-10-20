package com.sphereon.openid.fed.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/** Trusted host policy identity. None of these fields belong in editable or signed documents. */
@Serializable
data class AdminLifetimePolicyBinding(val ownerId: String, val federationId: String, val incarnationId: String)

@Serializable
enum class AdminLifetimeKind { ENTITY_CONFIGURATION, SUBORDINATE_STATEMENT }

/** The lifetime value is resolved from the owning policy; it is never copied into acknowledgement metadata. */
@Serializable
data class AdminLifetimePolicyContext(
    val binding: AdminLifetimePolicyBinding,
    val kind: AdminLifetimeKind,
    val revision: Long,
    val policyLifetimeSeconds: Long,
    val explicitLifetimeWrite: Boolean = false,
)

@Serializable
data class AdminLifetimeAcknowledgement(
    val binding: AdminLifetimePolicyBinding,
    val kind: AdminLifetimeKind,
    val revision: Long,
    val documentEtag: String,
    val documentIncarnation: String,
)

/** Internal owning-store address, never a standard administration collection. */
fun adminLifetimeAcknowledgementKey(key: AdminDocumentKey) =
    AdminDocumentKey("lifetime-policy-acknowledgement", key.collection + ":" + key.resourceId)

fun decodeAdminLifetimeAcknowledgement(document: kotlinx.serialization.json.JsonObject): AdminLifetimeAcknowledgement =
    Json.decodeFromJsonElement(document)

/** Host-owned lookup used by publication paths that do not originate from a document command. */
fun interface AdminLifetimePolicyResolver {
    suspend fun resolve(scope: AdminNodeScope, key: AdminDocumentKey): AdminLifetimePolicyContext?
}
