package com.sphereon.openid.fed.admin

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Err
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.trustMark.TrustMarkValidation
import dev.zacsweers.metro.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** One document workflow is shared by standalone and commercial command adapters. */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<AdminWorkflow>())
class DefaultAdminWorkflow(private val store: AdminDocumentStore, private val protocol: AdminProtocolPort) : AdminWorkflow {
    override suspend fun recoverKeyPreparations(scope: AdminNodeScope, abortPending: Boolean): IdkResult<Unit, AdminError> = safely {
        protocol.recoverKeyPreparations(scope, abortPending)
    }

    override suspend fun execute(operation: AdminOperation, args: AdminCommandArgs): IdkResult<AdminCommandResult, AdminError> = safely {
        if (args.scope.accountId.isBlank()) adminFail("invalid_request", 400, "A resolved node scope is required")
        run(operation, args)
    }
    override suspend fun stage(operation: AdminOperation, args: AdminCommandArgs): IdkResult<AdminCommandResult, AdminError> = safely {
        if (operation.method !in setOf("PUT", "POST", "PATCH") || !(operation.path.startsWith("/configuration") || operation.path.startsWith("/subordinates")))
            adminFail("invalid_request", 400, "Only signed configuration and subordinate writes can be staged")
        run(operation, args, stageOnly = true)
    }
    override suspend fun finalizeStage(scope: AdminNodeScope, digest: String, expectedStageEtag: String?): IdkResult<AdminCommandResult, AdminError> = safely {
        val committed = store.finalizeStage(scope, digest, expectedStageEtag) ?: adminFail("precondition_failed", 412, "Staged publication no longer matches canonical state")
        result(committed).copy(artifact = committed.artifact, stageDigest = digest)
    }
    override suspend fun stagedPublication(scope: AdminNodeScope, digest: String): IdkResult<AdminStagedPublication?, AdminError> = safely { store.stagedPublication(scope, digest) }
    override suspend fun pendingStages(scope: AdminNodeScope): IdkResult<List<AdminStagedPublication>, AdminError> = safely { store.pendingStages(scope) }

    override suspend fun selectSigningKey(scope: AdminNodeScope, keyId: String?, expectedRevision: Long): IdkResult<AdminCommandResult, AdminError> = safely {
        protocol.selectSigningKey(scope, keyId, expectedRevision)
    }

    override suspend fun withdrawSubordinate(scope: AdminNodeScope, entityId: String, edgeId: String?, expectedIntentVersion: Long?): IdkResult<AdminCommandResult, AdminError> = safely {
        if (entityId.isBlank()) adminFail("invalid_request", 400, "Subordinate Entity Identifier is required")
        val key = AdminDocumentKey("subordinate", entityId)
        var committed: AdminStoredDocument? = null
        repeat(16) {
            if (committed == null) {
                val old = store.read(scope, key)
                val document = JsonObject(old?.document.orEmpty() + mapOf("entity_id" to JsonPrimitive(entityId), "status" to JsonPrimitive("revoked")))
                val guardKey = if (edgeId != null && expectedIntentVersion != null) adminWithdrawalKey(entityId, edgeId, expectedIntentVersion) else null
                if ((edgeId == null) != (expectedIntentVersion == null)) adminFail("invalid_request", 400, "Edge and intent version must be supplied together")
                val guard = guardKey?.let { store.readCommitted(scope, it) }
                val writes = listOf(AdminMutation(key, old?.etag, document)) + if (guardKey != null) listOf(AdminMutation(guardKey, guard?.etag, buildJsonObject { put("withdrawn", true) })) else emptyList()
                committed = store.commit(scope, writes)?.firstOrNull()
            }
        }
        result(committed ?: adminFail("precondition_failed", 412, "Concurrent subordinate writes prevented withdrawal"))
    }

    private suspend fun <T> safely(block: suspend () -> T): IdkResult<T, AdminError> = try {
        IdkResult.ok(block())
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: AdminFailure) { Err(failure.problem).asResult() }
    catch (failure: Exception) { Err(AdminError("internal", 500, "Administration operation failed")).asResult() }

    private suspend fun run(op: AdminOperation, args: AdminCommandArgs, stageOnly: Boolean = false): AdminCommandResult {
        val scope = args.scope
        val node = protocol.node(scope)
        if (op == AdminOperation.ReadAdminNode) return AdminCommandResult(document = node)
        if (op.path.startsWith("/keys")) return protocol.keys(op, args)
        val roles = (node["roles"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        val collection = when {
            op.path.startsWith("/configuration") -> "configuration"
            op.path.startsWith("/subordinates") -> {
                if (roles.none { it == "trust_anchor" || it == "intermediate" })
                    adminFail("unsupported_resource", 404, "Node does not administer subordinates")
                "subordinate"
            }
            else -> {
                if ("trust_mark_issuer" !in roles) adminFail("unsupported_resource", 404, "Node does not issue Trust Marks")
                if (op.path.contains("/subjects")) "trust-mark-grant" else "trust-mark-type"
            }
        }
        val isList = op in setOf(AdminOperation.ListAdminSubordinates, AdminOperation.ListAdminTrustMarkTypes, AdminOperation.ListAdminTrustMarkGrants)
        if (isList) return page(args, collection)
        val supplied = args.document
        val resourceId = when (collection) {
            "configuration" -> ""
            "subordinate" -> args.resourceId ?: supplied?.string("entity_id")
            else -> args.resourceId ?: supplied?.string("trust_mark_type")
        } ?: adminFail("invalid_request", 400, "Resource identifier is required")
        val subjectId = if (collection == "trust-mark-grant") args.subjectId
            ?: adminFail("invalid_request", 400, "Subject Entity Identifier is required") else ""
        val key = AdminDocumentKey(collection, resourceId, subjectId)
        val old = store.read(scope, key)
        val isArtifact = op in setOf(AdminOperation.ReadAdminConfigurationStatement, AdminOperation.ReadAdminSubordinateStatement, AdminOperation.ReadAdminTrustMarkToken)
        if (isArtifact) {
            val artifact = old?.artifact ?: adminFail("not_published", 404, "Resource is not currently published")
            if (collection in setOf("subordinate", "trust-mark-grant") && old.document.string("status") != "active")
                adminFail("not_published", 404, "Resource is not currently published")
            if (adminArtifactExpired(artifact))
                adminFail("not_published", 404, "Published artifact has expired")
            return AdminCommandResult(artifact = artifact, mediaType = if (collection == "trust-mark-grant") "application/trust-mark+jwt" else "application/entity-statement+jwt")
        }
        if (op.method == "GET") return result(old ?: adminFail("not_found", 404, "Resource was not found"))
        if (!adminIfMatch(args.ifMatch, old?.etag))
            adminFail("precondition_failed", 412, "Resource revision does not match If-Match")
        val revoked = old?.document?.string("status") == "revoked"
        val isCreate = op.method == "POST"
        if (isCreate && old != null && !revoked) adminFail("already_exists", 409, "Resource already exists")
        val isStatus = op.path.endsWith("/status")
        if ((op.method == "PATCH" || isStatus || op.method == "DELETE") && (old == null || revoked))
            adminFail("not_found", 404, "Resource was not found")
        val next = if (op.method == "DELETE") JsonObject(old!!.document + ("status" to JsonPrimitive("revoked"))) else {
            val body = supplied ?: adminFail("invalid_request", 400, "A JSON object is required")
            if (isStatus) {
                if (body.keys.any { it !in setOf("status", "verify") }) adminFail("invalid_request", 400, "Status body may contain only status and verify")
                JsonObject(old!!.document + ("status" to (body["status"] ?: adminFail("invalid_request", 400, "status is required"))))
            } else if (op.method == "PATCH") mergeAdminPatch(old!!.document, body)
            else if (op.method == "PUT" && args.lifetimePolicy?.explicitLifetimeWrite == false && old != null && !revoked)
                JsonObject(old.document + body) // Retained publication preserves extensions owned by native document edits.
            else body
        }
        val clean = JsonObject(next.filterKeys { it !in setOf("updated_at", "etag", "verify") })
        if (containsNull(clean)) adminFail("invalid_request", 400, "JSON null is allowed only in merge patches")
        var resolved = buildJsonObject {
            clean.forEach { (k, v) -> put(k, v) }
            when (collection) {
                "subordinate" -> put("entity_id", resourceId)
                "trust-mark-type" -> put("trust_mark_type", resourceId)
                "trust-mark-grant" -> { put("entity_id", subjectId); put("trust_mark_type", resourceId) }
            }
            if (collection in setOf("subordinate", "trust-mark-grant") && "status" !in clean) put("status", "active")
        }
        val bodyId = if (collection == "subordinate" || collection == "trust-mark-grant") clean.string("entity_id") else clean.string("trust_mark_type")
        val expectedId = if (collection == "trust-mark-grant") subjectId else resourceId
        if (bodyId != null && collection != "configuration" && bodyId != expectedId)
            adminFail("invalid_request", 400, "Document identifier conflicts with resource path")
        val active = collection == "configuration" || (collection != "trust-mark-type" && resolved.string("status") == "active")
        if (collection in setOf("subordinate", "trust-mark-grant") && resolved.string("status") !in setOf("pending", "active", "suspended", "revoked"))
            adminFail("invalid_request", 400, "Invalid administration status", "/status")
        if (collection in setOf("subordinate", "trust-mark-grant") && old != null && !revoked) {
            val previousStatus = old.document.string("status") ?: "active"
            val nextStatus = resolved.string("status") ?: "active"
            val allowed = when (previousStatus) {
                "pending" -> setOf("pending", "active", "revoked")
                "active" -> setOf("active", "suspended", "revoked")
                "suspended" -> setOf("suspended", "active", "revoked")
                else -> emptySet()
            }
            if (nextStatus !in allowed) adminFail("conflict", 409, "Invalid lifecycle transition from $previousStatus to $nextStatus", "/status")
        }
        val queryVerify = args.query["verify"]?.let { it.toBooleanStrictOrNull() ?: adminFail("invalid_request", 400, "verify must be a boolean") }
        val bodyVerify = supplied?.get("verify")?.let { (it as? JsonPrimitive)?.booleanOrNull ?: adminFail("invalid_request", 400, "verify must be a boolean") }
        if (queryVerify != null && bodyVerify != null && queryVerify != bodyVerify) adminFail("invalid_request", 400, "Query and document verify must agree")
        val verify = queryVerify ?: bodyVerify ?: true
        if (!verify && active && collection != "configuration") adminFail("invalid_request", 400, "verify=false cannot publish an active subject")
        var parentDependency: Pair<AdminDocumentKey, String>? = null
        if (collection == "trust-mark-grant") {
            val type = store.read(scope, AdminDocumentKey("trust-mark-type", resourceId))
            if (type == null || type.document.string("status") == "revoked") adminFail("not_found", 404, "Trust Mark issuance type was not found")
            parentDependency = type.key to type.etag
        }
        val recreate = revoked && op.method in setOf("POST", "PUT") && !isStatus
        val lifetimePolicy = if (adminLifetimeKind(key) != null) args.lifetimePolicy ?: protocol.lifetimePolicy(scope, key)?.copy(
            explicitLifetimeWrite = !isStatus && op.method != "DELETE" &&
                (op.method == "PUT" || supplied?.containsKey("lifetime") == true)) else null
        val lifetime = applyAdminLifetimePolicy(key, old, resolved, lifetimePolicy,
            store.readLifetimeAcknowledgement(scope, key), active, recreate)
        resolved = lifetime.document
        if (op.method != "DELETE") protocol.validate(scope, key, resolved)
        if (active && collection in setOf("subordinate", "trust-mark-grant")) {
            val mustVerify = old == null || revoked || old.document.string("status") != "active" || isStatus ||
                (collection == "trust-mark-grant") || old.document["jwks"] != resolved["jwks"]
            if (mustVerify) protocol.verifySubject(scope, key, resolved)
        }
        val prepared = if (active) protocol.prepare(scope, key, resolved) else null
        val artifact = prepared?.artifact
        val storedDocument = if (collection == "trust-mark-grant" && artifact != null) {
            val claims = artifactClaims(artifact)
            JsonObject(resolved + claims.filterKeys { it in setOf("iat", "exp") })
        } else resolved
        val mutations = mutableListOf(AdminMutation(key, old?.etag, storedDocument, artifact, recreate = recreate,
            expectedDependencies = parentDependency?.let { mapOf(it) } ?: emptyMap(), expectedKeyState = prepared?.keyState, activationContext = args.activationContext,
            lifetimePolicy = lifetimePolicy, acknowledgeLifetimePolicy = lifetime.acknowledge))
        if (collection == "trust-mark-type" && op.method == "DELETE") {
            store.list(scope, "trust-mark-grant", resourceId).forEach { grant ->
                mutations += AdminMutation(grant.key, grant.etag, JsonObject(grant.document + ("status" to JsonPrimitive("revoked"))))
            }
        }
        if (stageOnly) {
            if (artifact == null) adminFail("invalid_request", 400, "Only published signed documents can be staged")
            val staged = store.stage(scope, mutations.first())
            return AdminCommandResult(document = storedDocument, etag = old?.etag, artifact = artifact, stageDigest = staged.digest)
        }
        val committed = store.commit(scope, mutations) ?: adminFail("precondition_failed", 412, "Resource changed during the operation")
        if (op.method == "DELETE") return AdminCommandResult(status = 204)
        val status = if (isCreate || (collection == "trust-mark-grant" && (old == null || revoked))) 201 else 200
        return result(committed.first()).copy(status = status,
            location = if (status == 201) location(scope, key) else null)
    }

    private fun result(row: AdminStoredDocument) = AdminCommandResult(document = buildJsonObject {
        row.document.forEach { (k, v) -> put(k, v) }; put("etag", row.etag); put("updated_at", row.updatedAt)
    }, etag = row.etag)

    private suspend fun page(args: AdminCommandArgs, collection: String): AdminCommandResult {
        val requested = args.query["limit"]?.toIntOrNull() ?: if (args.query.containsKey("limit")) adminFail("invalid_request", 400, "Invalid limit") else 100
        if (requested <= 0) adminFail("invalid_request", 400, "limit must be positive")
        val limit = requested.coerceAtMost(1000)
        var offset = args.query["cursor"]?.removePrefix("page:")?.toIntOrNull() ?: if (args.query.containsKey("cursor")) adminFail("invalid_request", 400, "Invalid cursor") else 0
        if (offset < 0) adminFail("invalid_request", 400, "Invalid cursor")
        val status = args.query["status"] ?: if (collection in setOf("subordinate", "trust-mark-grant")) "active" else null
        fun matches(row: AdminStoredDocument) =
            (status == null && row.document.string("status") != "revoked" || row.document.string("status") == status) &&
                (args.query["q"] == null || row.document.string("entity_id")?.contains(args.query.getValue("q")) == true) &&
                (args.query["entity_type"] == null || (row.document["metadata"] as? JsonObject)?.containsKey(args.query.getValue("entity_type")) == true)
        val selected = mutableListOf<AdminStoredDocument>()
        var next: Int? = null
        var scanned = 0
        while (scanned < 10_000) {
            val batch = store.page(args.scope, collection, if (collection == "trust-mark-grant") args.resourceId else null, offset, 1000)
            if (batch.isEmpty()) break
            var complete = false
            for (row in batch) {
                if (matches(row)) {
                    if (selected.size == limit) { next = offset; complete = true; break }
                    selected += row
                }
                offset++; scanned++
            }
            if (complete || batch.size < 1000) break
            if (scanned >= 10_000) next = offset
        }
        return AdminCommandResult(document = buildJsonObject {
            put("items", JsonArray(selected.map { row ->
                val doc = result(row).document!!
                if (collection == "subordinate" && args.query["detail"] != "full") JsonObject(doc - "jwks") else doc
            }))
            next?.let { put("next", "page:$it") }
        })
    }

    private fun location(scope: AdminNodeScope, key: AdminDocumentKey): String = scope.basePath + when(key.collection) {
        "configuration" -> "/configuration"
        "subordinate" -> "/subordinates/${encodeAdminSegment(key.resourceId)}"
        "trust-mark-type" -> "/trust-marks/${encodeAdminSegment(key.resourceId)}"
        else -> "/trust-marks/${encodeAdminSegment(key.resourceId)}/subjects/${encodeAdminSegment(key.subjectId)}"
    }
}

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** RFC 7396: object members recurse, null removes, arrays replace. */
fun mergeAdminPatch(target: JsonObject, patch: JsonObject): JsonObject = JsonObject(target.toMutableMap().apply {
    patch.forEach { (key, value) ->
        if (value == JsonNull) remove(key)
        else put(key, if (value is JsonObject) mergeAdminPatch(get(key) as? JsonObject ?: JsonObject(emptyMap()), value) else value)
    }
})

private fun containsNull(value: JsonElement): Boolean = when (value) {
    JsonNull -> true
    is JsonObject -> value.values.any(::containsNull)
    is JsonArray -> value.any(::containsNull)
    else -> false
}

fun encodeAdminSegment(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val n = byte.toInt() and 255
        if (n in 65..90 || n in 97..122 || n in 48..57 || n in listOf(45, 46, 95, 126)) append(n.toChar())
        else { append('%'); append("0123456789ABCDEF"[n shr 4]); append("0123456789ABCDEF"[n and 15]) }
    }
}

internal fun artifactClaims(artifact: String): JsonObject = Json.parseToJsonElement(kotlin.io.encoding.Base64.UrlSafe
    .withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
    .decode(artifact.split('.')[1]).decodeToString()).jsonObject

/** Preserve the signed NumericDate, including exponent notation and fractional seconds. */
internal fun adminArtifactExpired(artifact: String, now: kotlin.time.Instant = kotlin.time.Clock.System.now()): Boolean {
    val expiry = runCatching { TrustMarkValidation.numericDate(artifactClaims(artifact)["exp"]) }.getOrNull()
        ?: return true
    return expiry <= now.epochSeconds.toDouble() + now.nanosecondsOfSecond / 1_000_000_000.0
}

/** Extension claims remain signed; only administration and server-assigned fields are removed. */
internal fun adminSignedClaims(document: JsonObject): JsonObject = JsonObject(document.filterKeys {
    it !in setOf("entity_id", "status", "lifetime", "etag", "updated_at", "verify", "iss", "sub", "iat", "exp")
})

/** RFC 9110 strong If-Match comparison, including commas inside quoted opaque tags. */
internal fun adminIfMatch(condition: String?, current: String?): Boolean {
    if (condition == null) return true
    val value = condition.trim()
    if (value == "*") return current != null
    var index = 0
    var found = false
    var matched = false
    val tag = Regex("(?:W/)?\"[\\x21\\x23-\\x7E\\x80-\\xFF]*\"")
    while (index < value.length) {
        while (index < value.length && (value[index] == ' ' || value[index] == '\t' || value[index] == ',')) index++
        if (index == value.length) break
        val item = tag.find(value, index)?.takeIf { it.range.first == index }
            ?: adminFail("invalid_request", 400, "Malformed If-Match header")
        found = true
        if (!item.value.startsWith("W/") && item.value == current) matched = true
        index = item.range.last + 1
        while (index < value.length && (value[index] == ' ' || value[index] == '\t')) index++
        if (index < value.length && value[index] != ',') adminFail("invalid_request", 400, "Malformed If-Match header")
    }
    if (!found) adminFail("invalid_request", 400, "If-Match requires an entity tag")
    return matched
}

internal fun adminKeyEtag(state: String, kid: String): String = "\"$state:" + kotlin.io.encoding.Base64.UrlSafe
    .withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT).encode(kid.encodeToByteArray()) + "\""
