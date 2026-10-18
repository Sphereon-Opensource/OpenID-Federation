package com.sphereon.openid.fed.admin

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.AdminDocument
import dev.zacsweers.metro.*
import kotlinx.serialization.json.*
import java.util.Base64
import java.util.UUID

/** The account row serializes all writes, including concurrent first creates. No process-local lock is relied on. */
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<AdminDocumentStore>())
class SqlAdminDocumentStore internal constructor(
    private val cacheInvalidator: com.sphereon.openid.fed.services.command.renewal.PublishedStatementCacheInvalidator,
    private val database: com.sphereon.openid.fed.persistence.Database?,
) : AdminDocumentStore {
    @Inject constructor(cacheInvalidator: com.sphereon.openid.fed.services.command.renewal.PublishedStatementCacheInvalidator) : this(cacheInvalidator, null)
    private val queries get() = database?.adminDocumentQueries ?: Persistence.adminDocumentQueries
    private val accountQueries get() = database?.accountQueries ?: Persistence.accountQueries
    private val accountSigningKeyQueries get() = database?.accountSigningKeyQueries ?: Persistence.accountSigningKeyQueries
    private val authorityHintQueries get() = database?.authorityHintQueries ?: Persistence.authorityHintQueries
    private val entityConfigurationStatementQueries get() = database?.entityConfigurationStatementQueries ?: Persistence.entityConfigurationStatementQueries
    private val jwkQueries get() = database?.jwkQueries ?: Persistence.jwkQueries
    private val metadataQueries get() = database?.metadataQueries ?: Persistence.metadataQueries
    private val subordinateConstraintQueries get() = database?.subordinateConstraintQueries ?: Persistence.subordinateConstraintQueries
    private val subordinateJwkQueries get() = database?.subordinateJwkQueries ?: Persistence.subordinateJwkQueries
    private val subordinateMetadataQueries get() = database?.subordinateMetadataQueries ?: Persistence.subordinateMetadataQueries
    private val subordinateQueries get() = database?.subordinateQueries ?: Persistence.subordinateQueries
    private val subordinateStatementQueries get() = database?.subordinateStatementQueries ?: Persistence.subordinateStatementQueries
    private val trustMarkQueries get() = database?.trustMarkQueries ?: Persistence.trustMarkQueries
    private val trustMarkTypeQueries get() = database?.trustMarkTypeQueries ?: Persistence.trustMarkTypeQueries
    private fun keyState(accountId: String) = adminKeyState(accountId, jwkQueries, accountSigningKeyQueries, queries)
    override suspend fun readCommitted(scope: AdminNodeScope, key: AdminDocumentKey): AdminStoredDocument? =
        queries.find(scope.accountId, key.collection, key.resourceId, key.subjectId).executeAsOneOrNull()?.stored()
    override suspend fun listCommitted(scope: AdminNodeScope, collection: String, resourceId: String?): List<AdminStoredDocument> =
        (if (resourceId == null) queries.list(scope.accountId, collection) else queries.listByResource(scope.accountId, collection, resourceId)).executeAsList().map { it.stored() }
    override suspend fun read(scope: AdminNodeScope, key: AdminDocumentKey): AdminStoredDocument? {
        bootstrap(scope)
        return queries.find(scope.accountId, key.collection, key.resourceId, key.subjectId).executeAsOneOrNull()?.stored()
    }
    override suspend fun list(scope: AdminNodeScope, collection: String, resourceId: String?): List<AdminStoredDocument> {
        bootstrap(scope)
        return (if (resourceId == null) queries.list(scope.accountId, collection) else queries.listByResource(scope.accountId, collection, resourceId))
            .executeAsList().map { it.stored() }
    }
    override suspend fun page(scope: AdminNodeScope, collection: String, resourceId: String?, offset: Int, limit: Int): List<AdminStoredDocument> {
        bootstrap(scope)
        return (if (resourceId == null) queries.pageCollection(scope.accountId, collection, limit.toLong(), offset.toLong())
            else queries.pageResource(scope.accountId, collection, resourceId, limit.toLong(), offset.toLong())).executeAsList().map { it.stored() }
    }
    override suspend fun adopt(scope: AdminNodeScope, documents: List<AdminStoredDocument>): Boolean {
        require(documents.any { it.key == AdminDocumentKey("configuration") }) { "Trusted adoption requires configuration" }
        return accountQueries.transactionWithResult {
            val account = accountQueries.findActiveForUpdate(scope.accountId).executeAsOneOrNull()
                ?: adminFail("not_found", 404, "Node account was not found")
            if (queries.find(scope.accountId, "configuration", "", "").executeAsOneOrNull() != null) return@transactionWithResult false
            val suppliedEntity = documents.first { it.key == AdminDocumentKey("configuration") }.document.string("entity_id")
            if (scope.entityId != null && suppliedEntity != scope.entityId) adminFail("invalid_request", 400, "Adopted node identifier differs from trusted scope")
            if (account.identifier != null && suppliedEntity != account.identifier) adminFail("invalid_request", 400, "Adopted node identifier differs from its account")
            bootstrap(scope)
            documents.forEach { row ->
                queries.upsert(scope.accountId, row.key.collection, row.key.resourceId, row.key.subjectId,
                    JsonObject(row.document - setOf("etag", "updated_at")).toString(), "\"${UUID.randomUUID()}\"", System.currentTimeMillis() / 1000, row.artifact)
            }
            true
        }
    }
    override suspend fun commit(scope: AdminNodeScope, mutations: List<AdminMutation>): List<AdminStoredDocument>? {
        val result = commitDocuments(scope, mutations)
        (result?.second.orEmpty() + result?.first.orEmpty().mapNotNull { it.artifact }).distinct().forEach { cacheInvalidator.published(it) }
        return result?.first
    }
    private fun commitDocuments(scope: AdminNodeScope, mutations: List<AdminMutation>): Pair<List<AdminStoredDocument>, List<String>>? {
        bootstrap(scope)
        val result = accountQueries.transactionWithResult {
            val account = accountQueries.findActiveForUpdate(scope.accountId).executeAsOneOrNull()
                ?: adminFail("not_found", 404, "Node account was not found")
            if (scope.entityId != null && account.identifier != null && scope.entityId != account.identifier)
                adminFail("invalid_request", 400, "Resolved node scope does not match account")
            if (mutations.any { mutation ->
                mutation.expectedKeyState?.let { it != keyState(scope.accountId) } == true ||
                    mutation.expectedDependencies.any { (key, etag) -> queries.find(scope.accountId, key.collection, key.resourceId, key.subjectId).executeAsOneOrNull()?.etag != etag }
            }) return@transactionWithResult null
            val revokedTypes = mutations.filter { it.key.collection == "trust-mark-type" && it.document.string("status") == "revoked" }.map { it.key.resourceId }.toSet()
            val writes = mutations.filterNot { it.key.collection == "trust-mark-grant" && it.key.resourceId in revokedTypes }.toMutableList()
            // Read and revoke the CURRENT child set while holding the same account lock as grant writers.
            revokedTypes.forEach { type -> queries.listByResource(scope.accountId, "trust-mark-grant", type).executeAsList().forEach { row ->
                val old = row.stored()
                writes += AdminMutation(old.key, old.etag, JsonObject(old.document + ("status" to JsonPrimitive("revoked"))))
            }}
            val current = writes.map { mutation ->
                queries.findForUpdate(scope.accountId, mutation.key.collection, mutation.key.resourceId, mutation.key.subjectId).executeAsOneOrNull()
            }
            if (writes.indices.any { current[it]?.etag != writes[it].expectedEtag }) return@transactionWithResult null
            scope.subordinateLimit?.let { limit ->
                val existing = queries.list(scope.accountId, "subordinate").executeAsList().count { Json.parseToJsonElement(it.document).jsonObject.string("status") != "revoked" }
                val delta = writes.indices.filter { writes[it].key.collection == "subordinate" }.sumOf { index ->
                    val wasLive = current[index]?.let { Json.parseToJsonElement(it.document).jsonObject.string("status") != "revoked" } ?: false
                    val becomesLive = writes[index].document.string("status") != "revoked"
                    (if (becomesLive) 1 else 0) - (if (wasLive) 1 else 0)
                }
                if (delta > 0 && existing.toLong() + delta > limit) adminFail("conflict", 409, "Subordinate limit reached")
            }
            writes.mapNotNull { it.keyChange }.forEach { change ->
                change.newKey?.let { staged ->
                    if (jwkQueries.findAllByAccountId(scope.accountId).executeAsList().any { it.kid == staged.kid }) adminFail("already_exists", 409, "Key identifier already exists")
                    jwkQueries.createPrepared(staged.id, scope.accountId, staged.algorithm, staged.kid, staged.kms, staged.kmsKeyRef, staged.publicJwk.toString())
                }
                if (change.updateSelection) {
                    val selection = accountSigningKeyQueries.findByAccountId(scope.accountId).executeAsOneOrNull()
                    accountSigningKeyQueries.upsert(scope.accountId, change.selectedKeyId, (selection?.revision ?: 0) + 1).executeAsOne()
                }
                change.revokeKeyId?.let { keyId ->
                    val capable = jwkQueries.findByAccountId(scope.accountId).executeAsList().filter { it.kms.isNotBlank() && it.kms_key_ref.isNotBlank() && (it.expires_at == null || it.expires_at!!.toEpochSecond(java.time.ZoneOffset.UTC) > System.currentTimeMillis() / 1000) }
                    if (capable.any { it.id == keyId } && capable.none { it.id != keyId }) adminFail("last_signing_key", 409, "The last signing-capable key cannot be deleted")
                    jwkQueries.revoke("superseded", null, keyId).executeAsOne()
                }
            }
            val committed = writes.map { mutation ->
                val now = System.currentTimeMillis() / 1000
                val etag = "\"${UUID.randomUUID()}\""
                val key = mutation.key
                if (key.collection == "configuration") {
                    val desiredId = mutation.document.string("entity_id")
                    if (account.identifier != null && desiredId != null && account.identifier != desiredId)
                        adminFail("invalid_request", 400, "entity_id is immutable")
                    if (account.identifier == null && desiredId != null) accountQueries.initializeIdentifier(desiredId, scope.accountId)
                }
                queries.upsert(scope.accountId, key.collection, key.resourceId, key.subjectId,
                    mutation.document.toString(), etag, now, mutation.artifact)
                AdminStoredDocument(key, mutation.document, etag, now, mutation.artifact)
            }
            committed to current.mapNotNull { it?.artifact }
        }
        return result
    }

    override suspend fun stage(scope: AdminNodeScope, mutation: AdminMutation): AdminStagedPublication {
        require(mutation.key.collection in setOf("configuration", "subordinate") && mutation.keyChange == null)
        val artifact = mutation.artifact ?: adminFail("invalid_request", 400, "Staging requires a signed artifact")
        val digest = "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(artifact.toByteArray()).joinToString("") { "%02x".format(it) }
        return accountQueries.transactionWithResult {
            accountQueries.findActiveForUpdate(scope.accountId).executeAsOneOrNull() ?: adminFail("not_found", 404, "Node not found")
            val context = mutation.activationContext
            val edge = context?.string("edgeId")
            val version = (context?.get("intentVersion") as? JsonPrimitive)?.longOrNull
            if ((edge == null) != (version == null)) adminFail("invalid_request", 400, "Staged edge context requires an intent version")
            val guardKey = if (edge != null && version != null && mutation.key.collection == "subordinate") adminWithdrawalKey(mutation.key.resourceId, edge, version) else null
            if (guardKey != null && queries.find(scope.accountId, guardKey.collection, guardKey.resourceId, guardKey.subjectId).executeAsOneOrNull() != null)
                adminFail("precondition_failed", 412, "Captured subordinate intent was withdrawn")
            val fenced = mutation.copy(expectedDependencies = mutation.expectedDependencies + if (guardKey != null) mapOf(guardKey to null) else emptyMap())
            val document = stageDocument(fenced)
            if (queries.find(scope.accountId, mutation.key.collection, mutation.key.resourceId, mutation.key.subjectId).executeAsOneOrNull()?.etag != mutation.expectedEtag ||
                mutation.expectedKeyState?.let { it != keyState(scope.accountId) } == true || mutation.expectedDependencies.any { (key, etag) ->
                    queries.find(scope.accountId, key.collection, key.resourceId, key.subjectId).executeAsOneOrNull()?.etag != etag
                }) adminFail("precondition_failed", 412, "Canonical state changed before staging")
            val existing = queries.find(scope.accountId, "publication-candidate", digest, "").executeAsOneOrNull()
                ?: queries.find(scope.accountId, "publication-receipt", digest, "").executeAsOneOrNull()
            if (existing != null && (existing.artifact != artifact || Json.parseToJsonElement(existing.document).jsonObject.filterKeys { it !in setOf("stage_status", "committed_etag", "committed_at") } != document.filterKeys { it != "stage_status" }))
                adminFail("conflict", 409, "Artifact digest already identifies a different staged operation")
            val stageEtag = existing?.etag ?: "\"${UUID.randomUUID()}\""
            if (existing == null) queries.upsert(scope.accountId, "publication-candidate", digest, "", document.toString(), stageEtag, System.currentTimeMillis() / 1000, artifact)
            AdminStagedPublication(digest, mutation.key.collection, mutation.key.resourceId, mutation.key.subjectId, artifact, mutation.activationContext, existing?.collection == "publication-receipt", stageEtag)
        }
    }
    override suspend fun stagedPublication(scope: AdminNodeScope, digest: String): AdminStagedPublication? {
        val row = queries.find(scope.accountId, "publication-candidate", digest, "").executeAsOneOrNull()
            ?: queries.find(scope.accountId, "publication-receipt", digest, "").executeAsOneOrNull() ?: return null
        val doc = Json.parseToJsonElement(row.document).jsonObject
        val target = doc.getValue("target").jsonObject
        return AdminStagedPublication(digest, target.getValue("collection").jsonPrimitive.content,
            target.getValue("resource_id").jsonPrimitive.content, target.getValue("subject_id").jsonPrimitive.content,
            row.artifact ?: return null, doc["activation_context"] as? JsonObject, row.collection == "publication-receipt", row.etag)
    }
    override suspend fun pendingStages(scope: AdminNodeScope): List<AdminStagedPublication> = accountQueries.transactionWithResult {
        accountQueries.findActiveForUpdate(scope.accountId).executeAsOneOrNull() ?: adminFail("not_found", 404, "Node not found")
        queries.pendingStages(scope.accountId).executeAsList().mapNotNull { candidate ->
            val document = Json.parseToJsonElement(candidate.document).jsonObject
            val target = document.getValue("target").jsonObject
            val key = AdminDocumentKey(target.getValue("collection").jsonPrimitive.content, target.getValue("resource_id").jsonPrimitive.content, target.getValue("subject_id").jsonPrimitive.content)
            val canonical = queries.find(scope.accountId, key.collection, key.resourceId, key.subjectId).executeAsOneOrNull()
            val alreadyApplied = canonical?.artifact == candidate.artifact && canonical?.document == document.getValue("document").toString()
            val expired = candidate.artifact?.let { (payload(it)["exp"] as? JsonPrimitive)?.longOrNull?.let { exp -> exp <= System.currentTimeMillis() / 1000 } } != false
            val superseded = canonical?.etag != document.string("expected_etag") ||
                document.string("expected_key_state")?.let { it != keyState(scope.accountId) } == true ||
                (document["dependencies"] as? JsonArray).orEmpty().any { value ->
                    val dep = value.jsonObject
                    queries.find(scope.accountId, dep.getValue("collection").jsonPrimitive.content, dep.getValue("resource_id").jsonPrimitive.content, dep.getValue("subject_id").jsonPrimitive.content).executeAsOneOrNull()?.etag != dep.string("etag")
                }
            if (expired || (superseded && !alreadyApplied)) {
                queries.deleteHidden(scope.accountId, "publication-candidate", candidate.resource_id)
                null
            } else {
                // Fair bounded recovery: inspected-but-unaccepted proposals move behind uninspected proposals.
                queries.upsert(scope.accountId, "publication-candidate", candidate.resource_id, "", candidate.document, candidate.etag, System.currentTimeMillis(), candidate.artifact)
                AdminStagedPublication(candidate.resource_id, key.collection, key.resourceId, key.subjectId, candidate.artifact!!, document["activation_context"] as? JsonObject, false, candidate.etag)
            }
        }
    }
    override suspend fun finalizeStage(scope: AdminNodeScope, digest: String, expectedStageEtag: String?): AdminStoredDocument? {
        val invalidations = mutableSetOf<String>()
        val finalized = accountQueries.transactionWithResult {
        accountQueries.findActiveForUpdate(scope.accountId).executeAsOneOrNull() ?: adminFail("not_found", 404, "Node not found")
        val candidate = queries.find(scope.accountId, "publication-candidate", digest, "").executeAsOneOrNull()
            ?: queries.find(scope.accountId, "publication-receipt", digest, "").executeAsOneOrNull()
            ?: adminFail("not_found", 404, "Staged publication not found")
        if (expectedStageEtag != null && candidate.etag != expectedStageEtag) adminFail("precondition_failed", 412, "Staged publication changed")
        val stage = Json.parseToJsonElement(candidate.document).jsonObject
        val target = stage.getValue("target").jsonObject
        val key = AdminDocumentKey(target.getValue("collection").jsonPrimitive.content, target.getValue("resource_id").jsonPrimitive.content, target.getValue("subject_id").jsonPrimitive.content)
        val document = stage.getValue("document").jsonObject
        if (stage.string("stage_status") == "committed") return@transactionWithResult AdminStoredDocument(key, document,
            stage.getValue("committed_etag").jsonPrimitive.content, stage.getValue("committed_at").jsonPrimitive.long, candidate.artifact)
        val artifact = candidate.artifact ?: adminFail("not_published", 404, "Staged artifact is unavailable")
        if ((payload(artifact)["exp"] as? JsonPrimitive)?.longOrNull?.let { it <= System.currentTimeMillis() / 1000 } != false)
            adminFail("conflict", 409, "Staged artifact has expired")
        val dependencies = (stage["dependencies"] as? JsonArray).orEmpty().associate { value ->
            val dep = value.jsonObject
            AdminDocumentKey(dep.getValue("collection").jsonPrimitive.content, dep.getValue("resource_id").jsonPrimitive.content, dep.getValue("subject_id").jsonPrimitive.content) to dep.string("etag")
        }
        val mutation = AdminMutation(key, stage.string("expected_etag"), document, artifact,
            expectedDependencies = dependencies + (AdminDocumentKey("publication-candidate", digest) to candidate.etag), expectedKeyState = stage.string("expected_key_state"))
        queries.find(scope.accountId, key.collection, key.resourceId, key.subjectId).executeAsOneOrNull()?.artifact?.let { invalidations += it }
        val committed = commitDocuments(scope, listOf(mutation))?.first?.firstOrNull() ?: return@transactionWithResult null
        committed.artifact?.let { invalidations += it }
        accountQueries.transaction {
            accountQueries.findActiveForUpdate(scope.accountId).executeAsOneOrNull() ?: adminFail("not_found", 404, "Node not found")
            val updated = JsonObject(stage + mapOf("stage_status" to JsonPrimitive("committed"), "committed_etag" to JsonPrimitive(committed.etag), "committed_at" to JsonPrimitive(committed.updatedAt)))
            queries.upsert(scope.accountId, "publication-receipt", digest, "", updated.toString(), candidate.etag, System.currentTimeMillis(), artifact)
            queries.deleteHidden(scope.accountId, "publication-candidate", digest)
            queries.trimReceipts(scope.accountId, scope.accountId)
        }
        committed
        }
        invalidations.forEach { cacheInvalidator.published(it) }
        return finalized
    }
    private fun stageDocument(mutation: AdminMutation): JsonObject = buildJsonObject {
        put("stage_status", "pending")
        put("target", buildJsonObject { put("collection", mutation.key.collection); put("resource_id", mutation.key.resourceId); put("subject_id", mutation.key.subjectId) })
        put("document", mutation.document)
        mutation.expectedEtag?.let { put("expected_etag", it) }
        mutation.expectedKeyState?.let { put("expected_key_state", it) }
        mutation.activationContext?.let { put("activation_context", it) }
        put("dependencies", JsonArray(mutation.expectedDependencies.map { (key, etag) -> buildJsonObject {
            put("collection", key.collection); put("resource_id", key.resourceId); put("subject_id", key.subjectId); etag?.let { put("etag", it) }
        }}))
    }

    /** Upgrade existing installations from their persisted artifacts/components without network or re-signing.
     * The first account access takes its row lock; configuration is the per-account migration marker.
     * Existing JWT bytes and publication eligibility are retained exactly.
     */
    private fun bootstrap(scope: AdminNodeScope) {
        if (queries.find(scope.accountId, "configuration", "", "").executeAsOneOrNull() != null) return
        accountQueries.transaction {
            val account = accountQueries.findActiveForUpdate(scope.accountId).executeAsOneOrNull()
                ?: adminFail("not_found", 404, "Node account was not found")
            if (queries.find(scope.accountId, "configuration", "", "").executeAsOneOrNull() != null) return@transaction
            if (account.identifier == null && scope.entityId != null) accountQueries.initializeIdentifier(scope.entityId, scope.accountId)
            fun put(key: AdminDocumentKey, doc: JsonObject, artifact: String?) {
                queries.upsert(scope.accountId, key.collection, key.resourceId, key.subjectId, doc.toString(),
                    "\"${UUID.randomUUID()}\"", System.currentTimeMillis() / 1000, artifact)
            }
            val configuration = entityConfigurationStatementQueries.findLatestByAccountId(scope.accountId).executeAsOneOrNull()
            val configDoc = configuration?.statement?.let(::payload) ?: buildJsonObject {
                account.identifier?.let { put("entity_id", it) }
                val metadata = metadataQueries.findByAccountId(scope.accountId).executeAsList()
                if (metadata.isNotEmpty()) put("metadata", JsonObject(metadata.associate { it.key to Json.parseToJsonElement(it.metadata) }))
                val hints = authorityHintQueries.findByAccountId(scope.accountId).executeAsList()
                if (hints.isNotEmpty()) put("authority_hints", JsonArray(hints.map { JsonPrimitive(it.identifier) }))
            }
            val normalized = buildJsonObject {
                configDoc.filterKeys { it !in setOf("iss", "sub", "iat", "exp", "jwks") }.forEach { (k,v) -> put(k,v) }
                (account.identifier ?: scope.entityId ?: configDoc.string("iss"))?.let { put("entity_id", it) }
            }
            put(AdminDocumentKey("configuration"), normalized, configuration?.statement)
            subordinateQueries.findByAccountId(scope.accountId).executeAsList().forEach { subordinate ->
                val statement = subordinateStatementQueries.findLatestBySubordinateId(subordinate.id).executeAsOneOrNull()
                val doc = buildJsonObject {
                    statement?.statement?.let(::payload)?.filterKeys { it !in setOf("iss", "sub", "iat", "exp") }?.forEach { (k,v) -> put(k,v) }
                    put("entity_id", subordinate.identifier)
                    put("status", if (statement != null) "active" else "pending")
                    if (statement == null) {
                        val jwks = subordinateJwkQueries.findBySubordinateId(subordinate.id).executeAsList()
                        if (jwks.isNotEmpty()) put("jwks", buildJsonObject { put("keys", JsonArray(jwks.map { Json.parseToJsonElement(it.key) })) })
                        val metadata = subordinateMetadataQueries.findByAccountIdAndSubordinateId(scope.accountId, subordinate.id).executeAsList()
                        if (metadata.isNotEmpty()) put("metadata", JsonObject(metadata.associate { it.key to Json.parseToJsonElement(it.metadata) }))
                        subordinateConstraintQueries.findBySubordinateId(subordinate.id).executeAsOneOrNull()?.let { put("constraints", Json.parseToJsonElement(it.constraints)) }
                    }
                }
                put(AdminDocumentKey("subordinate", subordinate.identifier), doc, statement?.statement)
            }
            trustMarkTypeQueries.findByAccountId(scope.accountId).executeAsList().forEach { type ->
                put(AdminDocumentKey("trust-mark-type", type.identifier), buildJsonObject {
                    put("trust_mark_type", type.identifier); type.delegation?.let { put("delegation", it) }
                }, null)
            }
            trustMarkQueries.findByAccountId(scope.accountId).executeAsList().sortedBy { it.iat }.forEach { mark ->
                val doc = buildJsonObject {
                    payload(mark.trust_mark_value).filterKeys { it !in setOf("iss", "sub") }.forEach { (k,v) -> put(k,v) }
                    put("entity_id", mark.sub); put("trust_mark_type", mark.trust_mark_id); put("status", "active")
                }
                put(AdminDocumentKey("trust-mark-grant", mark.trust_mark_id, mark.sub), doc, mark.trust_mark_value)
            }
        }
    }
    private fun AdminDocument.stored() = AdminStoredDocument(
        AdminDocumentKey(collection, resource_id, subject_id), Json.parseToJsonElement(document).jsonObject, etag, updated_at, artifact)
}

internal fun payload(jwt: String): JsonObject = Json.parseToJsonElement(
    String(Base64.getUrlDecoder().decode(jwt.split('.')[1]), Charsets.UTF_8)).jsonObject
