package com.sphereon.openid.fed.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class AdminDocumentKey(val collection: String, val resourceId: String = "", val subjectId: String = "")

@Serializable
data class AdminStoredDocument(
    val key: AdminDocumentKey,
    val document: JsonObject,
    val etag: String,
    val updatedAt: Long,
    val artifact: String? = null,
)

data class AdminMutation(
    val key: AdminDocumentKey,
    val expectedEtag: String?,
    val document: JsonObject,
    val artifact: String? = null,
    val recreate: Boolean = false,
    val expectedDependencies: Map<AdminDocumentKey, String?> = emptyMap(),
    val expectedKeyState: String? = null,
    val keyChange: AdminKeyChange? = null,
    val activationContext: JsonObject? = null,
    val lifetimePolicy: AdminLifetimePolicyContext? = null,
    val acknowledgeLifetimePolicy: Boolean = false,
)

data class AdminPreparedArtifact(val artifact: String, val keyState: String)
data class AdminPreparedKey(val id: String, val publicJwk: JsonObject, val kid: String, val algorithm: String?, val kms: String, val kmsKeyRef: String)
data class AdminKeyChange(val newKey: AdminPreparedKey? = null, val selectedKeyId: String? = null, val updateSelection: Boolean = false, val revokeKeyId: String? = null)

/** All compare-and-swap writes and artifact visibility changes commit in one owning-store transaction. */
interface AdminDocumentStore {
    /** Hidden metadata, read with the canonical document and fenced again in the owning-store commit. */
    suspend fun readLifetimeAcknowledgement(scope: AdminNodeScope, key: AdminDocumentKey): AdminLifetimeAcknowledgement? =
        readCommitted(scope, adminLifetimeAcknowledgementKey(key))?.let { decodeAdminLifetimeAcknowledgement(it.document) }

    /** Internal, read-only key authority; never exposed by a standard document operation. */
    suspend fun readKeyMaterial(scope: AdminNodeScope, keyId: String): AdminStoredKeyMaterial? = null
    suspend fun recoverableKeyPreparations(scope: AdminNodeScope, limit: Int): List<AdminStoredDocument> =
        listCommitted(scope, "key-preparation").filter {
            it.document["status"] in setOf(JsonPrimitive("PENDING"), JsonPrimitive("ABORTED")) && it.document["cleanup_complete"] != JsonPrimitive(true)
        }.take(limit)
    /** Scheduling fairness only; must not change the authority revision or document. */
    suspend fun touchKeyPreparation(scope: AdminNodeScope, keyId: String) = Unit

    /** Trusted host upgrade import; succeeds only if no canonical configuration has been committed. */
    suspend fun adopt(scope: AdminNodeScope, documents: List<AdminStoredDocument>): Boolean
    suspend fun stage(scope: AdminNodeScope, mutation: AdminMutation): AdminStagedPublication = adminFail("internal", 500, "Store does not support staging")
    suspend fun finalizeStage(scope: AdminNodeScope, digest: String, expectedStageEtag: String? = null): AdminStoredDocument? = adminFail("internal", 500, "Store does not support staging")
    suspend fun stagedPublication(scope: AdminNodeScope, digest: String): AdminStagedPublication? = null
    suspend fun pendingStages(scope: AdminNodeScope): List<AdminStagedPublication> = emptyList()
    /** Read-only protocol view: never imports, initializes, signs or mutates. */
    suspend fun readCommitted(scope: AdminNodeScope, key: AdminDocumentKey): AdminStoredDocument?
    suspend fun listCommitted(scope: AdminNodeScope, collection: String, resourceId: String? = null): List<AdminStoredDocument>
    suspend fun read(scope: AdminNodeScope, key: AdminDocumentKey): AdminStoredDocument?
    suspend fun list(scope: AdminNodeScope, collection: String, resourceId: String? = null): List<AdminStoredDocument>
    suspend fun page(scope: AdminNodeScope, collection: String, resourceId: String?, offset: Int, limit: Int): List<AdminStoredDocument> =
        list(scope, collection, resourceId).drop(offset).take(limit)
    /** null means an optimistic conflict; no mutation may have committed in that case. */
    suspend fun commit(scope: AdminNodeScope, mutations: List<AdminMutation>): List<AdminStoredDocument>?
}

/** Domain operations below the document workflow. Preparation never publishes or changes document state. */
interface AdminProtocolPort {
    /** Trusted host resolution for direct native commands and managed renewal; standalone nodes return null. */
    suspend fun lifetimePolicy(scope: AdminNodeScope, key: AdminDocumentKey): AdminLifetimePolicyContext? = null

    suspend fun recoverKeyPreparations(scope: AdminNodeScope, abortPending: Boolean = false) = Unit

    suspend fun selectSigningKey(scope: AdminNodeScope, keyId: String?, expectedRevision: Long): AdminCommandResult = adminFail("internal", 500, "Signing selection is unavailable")
    suspend fun node(scope: AdminNodeScope): JsonObject
    suspend fun keys(operation: AdminOperation, args: AdminCommandArgs): AdminCommandResult
    suspend fun validate(scope: AdminNodeScope, key: AdminDocumentKey, document: JsonObject)
    suspend fun verifySubject(scope: AdminNodeScope, key: AdminDocumentKey, document: JsonObject)
    suspend fun prepare(scope: AdminNodeScope, key: AdminDocumentKey, document: JsonObject): AdminPreparedArtifact
}

class AdminFailure(val problem: AdminError) : RuntimeException(problem.detail)

fun adminFail(token: String, status: Int, detail: String, pointer: String? = null): Nothing =
    throw AdminFailure(AdminError(token, status, detail, pointer))

/** A permanent fence for one captured commercial intent generation. */
fun adminWithdrawalKey(entityId: String, edgeId: String, intentVersion: Long) =
    AdminDocumentKey("control-withdrawal", entityId, "$edgeId:$intentVersion")
