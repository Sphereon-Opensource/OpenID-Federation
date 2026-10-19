package com.sphereon.openid.fed.admin

import com.sphereon.crypto.core.kms.ManagedKeyCreationAttempt
import com.sphereon.crypto.core.kms.ManagedKeyCreationAttemptState
import com.sphereon.crypto.core.kms.ManagedKeyCreationAttemptIntent
import kotlinx.coroutines.withContext
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.jose.JwaAlgorithm
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.services.CreateKeyArgs
import com.sphereon.openid.fed.services.command.jwk.CreateKeyCommandArgs
import com.sphereon.openid.fed.services.command.jwk.CreateKeyCommandImpl
import com.sphereon.openid.fed.services.signPayload
import dev.zacsweers.metro.*
import kotlinx.serialization.json.*

/** Separate collaborator survives replacement of the default interface binding in a host. */
@Inject @SingleIn(SessionScope::class)
class DefaultAdminKeyMaterialSupport(
    private val store: AdminDocumentStore,
    private val createKey: CreateKeyCommandImpl,
    private val jwt: JwtService,
) : AdminKeyMaterialPort {
    override suspend fun prepare(scope: AdminNodeScope, keyId: String, algorithm: String): AdminPreparedKey {
        val signature = SignatureAlgorithm.fromJose(JwaAlgorithm.fromValue(algorithm)
            ?: adminFail("invalid_request", 400, "Unsupported signing algorithm"))
        val generated = createKey.prepareKeyWithContext(CreateKeyCommandArgs(scope.accountId,
            CreateKeyArgs(alias = "oidfed-$keyId", alg = signature)), beforeGenerate = { providerId, alias ->
            val before = store.readCommitted(scope, adminKeyPreparationKey(keyId))
                ?: adminFail("precondition_failed", 412, "Key preparation is unavailable")
            if (before.document["status"] != JsonPrimitive("PENDING")) adminFail("precondition_failed", 412, "Key preparation was aborted")
            val bound = buildJsonObject {
                before.document.forEach { (name, value) -> put(name, value) }
                put("kms", providerId); put("kms_key_ref", requireNotNull(alias))
            }
            if (store.commit(scope, listOf(AdminMutation(before.key, before.etag, bound))) == null)
                adminFail("precondition_failed", 412, "Key preparation changed")
        }, generationContext = { provider, alias -> attempt(scope, keyId, provider, requireNotNull(alias), false) {} })
        if (generated.isErr) adminFail("internal", generated.error.httpStatusValue, generated.error.message.defaultMessage)
        val key = generated.value
        return AdminPreparedKey(keyId, Json.parseToJsonElement(key.publicJwk).jsonObject, key.kid, key.algorithm, key.kms, key.kmsKeyRef)
    }
    override suspend fun sign(scope: AdminNodeScope, key: AdminPreparedKey, header: JsonObject, claims: JsonObject): String {
        val signed = jwt.signPayload(claims, Json.decodeFromJsonElement(JwtHeader.serializer(), header), key.kid, key.kmsKeyRef, key.kms)
        if (signed.isErr) adminFail("internal", signed.error.httpStatusValue, signed.error.message.defaultMessage)
        return signed.value
    }
    override suspend fun discard(scope: AdminNodeScope, keyId: String): Boolean {
        val preparation = store.readCommitted(scope, adminKeyPreparationKey(keyId)) ?: return true
        val doc = preparation.document
        if (doc["status"] != JsonPrimitive("ABORTED") || doc["generated"] != JsonPrimitive(true))
            adminFail("conflict", 409, "Only aborted generated preparations may be discarded")
        val provider = doc["kms"]?.jsonPrimitive?.contentOrNull ?: return true // no provider was entered
        val alias = doc["kms_key_ref"]?.jsonPrimitive?.contentOrNull ?: return false
        val algorithm = doc["algorithm"]?.jsonPrimitive?.contentOrNull?.let(JwaAlgorithm::fromValue)?.let(SignatureAlgorithm::fromJose)
            ?: adminFail("internal", 500, "Uncommitted key algorithm is unavailable")
        var conclusivelyRevoked = false
        val result = withContext(attempt(scope, keyId, provider, alias, true) { conclusivelyRevoked = true }) {
            createKey.discardPreparedKeyWithProof(provider, alias, doc["kid"]?.jsonPrimitive?.contentOrNull, algorithm)
        }
        if (conclusivelyRevoked) return true
        if (result.isErr) adminFail("internal", result.error.httpStatusValue, result.error.message.defaultMessage)
        return result.value
    }
    private suspend fun attempt(scope: AdminNodeScope, keyId: String, provider: String, alias: String,
        revocation: Boolean, confirmRevoked: suspend () -> Unit): ManagedKeyCreationAttempt {
        val initial = store.readCommitted(scope, adminKeyPreparationKey(keyId))
            ?: adminFail("precondition_failed", 412, "Key preparation disappeared")
        val token = initial.document["preparation_id"]?.jsonPrimitive?.contentOrNull
            ?: adminFail("internal", 500, "Missing key preparation identity")
        suspend fun current(): AdminStoredDocument? {
            val row = store.readCommitted(scope, initial.key) ?: return null
            val d = row.document
            if (d["preparation_id"] != JsonPrimitive(token) || d["account_id"] != JsonPrimitive(scope.accountId) ||
                d["key_id"] != JsonPrimitive(keyId) || d["entity_identifier"] != scope.entityId?.let(::JsonPrimitive) ||
                d["kms"] != JsonPrimitive(provider) || d["kms_key_ref"] != JsonPrimitive(alias) || d["generated"] != JsonPrimitive(true) ||
                d["status"] != JsonPrimitive(if (revocation) "ABORTED" else "PENDING")) return null
            val incarnation = store.readCommitted(scope, AdminDocumentKey("node-incarnation"))
            if (incarnation?.document?.get("id") != d["node_incarnation"]) return null
            if (!revocation && (store.readCommitted(scope, AdminDocumentKey("configuration"))?.etag != d["configuration_revision"]?.jsonPrimitive?.contentOrNull ||
                d["lease_expires_at"]?.jsonPrimitive?.longOrNull?.let { it > System.currentTimeMillis() / 1000 } != true)) return null
            return row
        }
        return ManagedKeyCreationAttempt(provider, alias, token, readState = {
            val row = current()
            when (row?.document?.get("generation_attempt_token")) {
                null -> if (row == null) ManagedKeyCreationAttemptState.REVOKED else ManagedKeyCreationAttemptState.UNCLAIMED
                JsonPrimitive(token) -> ManagedKeyCreationAttemptState.CLAIMED
                else -> ManagedKeyCreationAttemptState.REVOKED
            }
        }, claim = {
            val row = current()
            if (revocation || row == null || row.document["generation_attempt_token"] != null) false else {
                val incarnation = store.readCommitted(scope, AdminDocumentKey("node-incarnation"))
                val committed = store.commit(scope, listOf(AdminMutation(row.key, row.etag, JsonObject(row.document + mapOf(
                    "generation_attempt_token" to JsonPrimitive(token), "generation_attempt_claimed_at" to JsonPrimitive(System.currentTimeMillis() / 1000))),
                    expectedDependencies = mapOf(AdminDocumentKey("configuration") to row.document["configuration_revision"]?.jsonPrimitive?.contentOrNull,
                        AdminDocumentKey("node-incarnation") to incarnation?.etag))))
                committed != null && current()?.document?.get("generation_attempt_token") == JsonPrimitive(token)
            }
        }, recover = { current() != null }, intent = if (revocation) ManagedKeyCreationAttemptIntent.RECOVER_FOR_REVOCATION else ManagedKeyCreationAttemptIntent.CREATE_OR_RECOVER,
            confirmRevoked = { if (current() == null) adminFail("precondition_failed", 412, "Key cleanup authority changed"); confirmRevoked() })
    }

}

@Inject @SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<AdminKeyMaterialPort>())
class DefaultAdminKeyMaterialPort(private val support: DefaultAdminKeyMaterialSupport) : AdminKeyMaterialPort by support
