package com.sphereon.openid.fed.admin

import com.sphereon.core.api.IdkResult
import com.sphereon.crypto.core.jose.hasWellFormedPublicJwkMaterial
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.jose.JwaAlgorithm
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.wallet.policy.MetadataPolicyOperators
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkCommand
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkArgs
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkDelegationCommand
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkDelegationArgs
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationArgs
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.command.trustChain.EntityStatementValidation
import com.sphereon.openid.fed.client.command.trustChain.NamingConstraintsMatcher
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.isValidEntityIdentifier
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.Jwk
import com.sphereon.openid.fed.services.CreateKeyArgs
import com.sphereon.openid.fed.services.command.jwk.*
import com.sphereon.openid.fed.services.command.trust.VerifyEntityTrustArgs
import com.sphereon.openid.fed.services.command.trust.VerifyEntityTrustCommand
import com.sphereon.openid.fed.services.command.registration.RegistrationTrustAnchor
import com.sphereon.openid.fed.services.command.StatementSigningValidation
import com.sphereon.openid.fed.services.signPayload
import dev.zacsweers.metro.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.sphereon.crypto.jose.jws.JwsCompact
import com.sphereon.crypto.jose.jws.command.VerifyJwsArgs

/** Standalone protocol adapter: existing KMS, federation verification and signing implementations are reused. */
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<AdminProtocolPort>())
class StandaloneAdminProtocolPort internal constructor(
    private val store: AdminDocumentStore,
    private val tenantResolver: TenantContextResolver,
    private val config: OidfConfigBinder,
    private val createKey: CreateKeyCommandImpl,
    private val jwt: JwtService,
    private val fetchConfiguration: GetEntityConfigurationCommand,
    private val verifyTrust: VerifyEntityTrustCommand,
    private val verifyDelegation: VerifyTrustMarkDelegationCommand,
    private val verifyMark: VerifyTrustMarkCommand,
    private val database: com.sphereon.openid.fed.persistence.Database?,
    private val clock: () -> Long,
    private val keyMaterial: AdminKeyMaterialPort? = null,
) : AdminProtocolPort {
    @Inject constructor(store: AdminDocumentStore, tenantResolver: TenantContextResolver, config: OidfConfigBinder,
        createKey: CreateKeyCommandImpl, jwt: JwtService, fetchConfiguration: GetEntityConfigurationCommand,
        verifyTrust: VerifyEntityTrustCommand, verifyDelegation: VerifyTrustMarkDelegationCommand, verifyMark: VerifyTrustMarkCommand, keyMaterial: AdminKeyMaterialPort) :
        this(store, tenantResolver, config, createKey, jwt, fetchConfiguration, verifyTrust, verifyDelegation, verifyMark, null, { System.currentTimeMillis() / 1000 }, keyMaterial)
    private val materials: AdminKeyMaterialPort = keyMaterial ?: DefaultAdminKeyMaterialSupport(store, createKey, jwt)
    private val keyQueries get() = database?.jwkQueries ?: Persistence.jwkQueries
    private val selectionQueries get() = database?.accountSigningKeyQueries ?: Persistence.accountSigningKeyQueries
    private val documentQueries get() = database?.adminDocumentQueries ?: Persistence.adminDocumentQueries
    private fun keyState(accountId: String) = adminKeyState(accountId, keyQueries, selectionQueries, documentQueries)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private suspend fun identifier(scope: AdminNodeScope): String? {
        val resolved = tenantResolver.resolveIdentifier(scope.accountId)
        if (scope.entityId != null && resolved != null && scope.entityId != resolved)
            adminFail("invalid_request", 400, "Resolved node identifier conflicts with its account")
        return resolved ?: scope.entityId ?: store.read(scope, AdminDocumentKey("configuration"))?.document?.string("entity_id")
    }
    private fun keys(scope: AdminNodeScope): List<Jwk> = keyQueries.findAllByAccountId(scope.accountId).executeAsList()
    private fun capable(key: Jwk): Boolean = key.revoked_at == null && key.kms.isNotBlank() && key.kms_key_ref.isNotBlank() &&
        (key.expires_at == null || key.expires_at!!.toEpochSecond(java.time.ZoneOffset.UTC) > now())
    private fun selected(scope: AdminNodeScope): Jwk? {
        val candidates = keys(scope).filter(::capable)
        val selection = selectionQueries.findByAccountId(scope.accountId).executeAsOneOrNull()
        return candidates.find { it.id == selection?.jwk_id } ?: candidates.firstOrNull()
    }
    private suspend fun applyDueRollover(scope: AdminNodeScope) {
        for (state in store.list(scope, "key-state")) {
            val switchAt = (state.document["switch_at"] as? JsonPrimitive)?.longOrNull ?: continue
            if (switchAt > now() || state.document["switched"] == JsonPrimitive(true)) continue
            val fence = keyState(scope.accountId)
            val replacement = keys(scope).find { it.kid == state.document.string("new_kid") && capable(it) } ?: continue
            val configuration = store.read(scope, AdminDocumentKey("configuration")) ?: continue
            val artifact = if (configuration.artifact != null) prepareWith(scope, configuration.key, configuration.document, replacement, publicJwks(scope), fence).artifact else null
            val changed = AdminMutation(configuration.key, configuration.etag, configuration.document, artifact,
                expectedKeyState = fence, keyChange = AdminKeyChange(selectedKeyId = replacement.id, updateSelection = true))
            val switched = AdminMutation(state.key, state.etag, JsonObject(state.document + ("switched" to JsonPrimitive(true))))
            if (store.commit(scope, listOf(changed, switched)) == null) adminFail("precondition_failed", 412, "Key state changed during rollover")
        }
    }
    override suspend fun node(scope: AdminNodeScope): JsonObject {
        recoverKeyPreparations(scope)
        applyDueRollover(scope)
        val doc = store.read(scope, AdminDocumentKey("configuration"))?.document ?: JsonObject(emptyMap())
        val federation = ((doc["metadata"] as? JsonObject)?.get("federation_entity") as? JsonObject) ?: JsonObject(emptyMap())
        val superior = federation.containsKey("federation_fetch_endpoint") && federation.containsKey("federation_list_endpoint")
        val hints = (doc["authority_hints"] as? JsonArray).orEmpty()
        val roles = mutableListOf(if (superior) if (hints.isEmpty()) "trust_anchor" else "intermediate" else "leaf")
        if (federation.containsKey("federation_trust_mark_endpoint") || federation.containsKey("federation_trust_mark_status_endpoint") || store.list(scope, "trust-mark-type").isNotEmpty()) roles += "trust_mark_issuer"
        if (federation.containsKey("federation_resolve_endpoint")) roles += "resolver"
        return buildJsonObject {
            identifier(scope)?.let { put("entity_id", it); selected(scope)?.kid?.let { kid -> put("signing_kid", kid) } }
            put("roles", JsonArray(roles.map(::JsonPrimitive))); put("base", scope.basePath); put("spec", "draft-kodden-oidfed-admin-00")
            put("implementation", buildJsonObject { put("name", "Sphereon OpenID Federation"); put("version", "0.26.0-SNAPSHOT") })
            put("capabilities", buildJsonObject {
                put("keys", JsonArray(listOf("list", "create", "read", "rotate", "delete").map(::JsonPrimitive)))
                put("configuration", JsonArray(listOf("read", "replace", "patch", "statement").map(::JsonPrimitive)))
                if (superior) put("subordinates", JsonArray(listOf("list", "create", "read", "replace", "patch", "delete", "status", "statement").map(::JsonPrimitive)))
                if ("trust_mark_issuer" in roles) put("trust_marks", JsonArray(listOf("list", "create", "read", "replace", "delete", "grant", "status", "token").map(::JsonPrimitive)))
            })
        }
    }

    override suspend fun validate(scope: AdminNodeScope, key: AdminDocumentKey, document: JsonObject) {
        for (member in listOf("metadata", "metadata_policy", "jwks", "constraints", "trust_mark_issuers", "trust_mark_owners")) {
            if (member in document && document[member] !is JsonObject) invalid("$member must be a JSON object", "/$member")
        }
        for (member in listOf("authority_hints", "trust_anchor_hints", "crit")) {
            document[member]?.let { value ->
                if (value !is JsonArray || value.any { it !is JsonPrimitive || !it.isString || it.content.isBlank() })
                    invalid("$member must be an array of nonempty strings", "/$member")
            }
        }
        document["metadata"]?.jsonObject?.forEach { (type, metadata) ->
            if (type.isBlank() || metadata !is JsonObject) invalid("metadata entity types must contain objects", "/metadata")
        }
        document["constraints"]?.let {
            val parsed = EntityStatementValidation.parseConstraints(it)
            if (parsed.constraints == null) invalid(parsed.reason ?: "Invalid constraints", "/constraints")
        }
        (document["metadata_policy"] as? JsonObject)?.let { policy ->
            val merged = MetadataPolicyOperators.mergePolicies(JsonObject(emptyMap()), policy)
            if (merged is MetadataPolicyOperators.PolicyMergeResult.Error) invalid(merged.reason, "/metadata_policy")
        }
        val entity = document.string("entity_id")
        if (key.collection != "trust-mark-type" && entity != null && !isValidEntityIdentifier(entity)) invalid("Invalid Entity Identifier", "/entity_id")
        val nodeId = identifier(scope)
        if (key.collection == "configuration" && nodeId != null && entity != null && nodeId != entity)
            adminFail("invalid_request", 400, "entity_id is immutable after initialization", "/entity_id")
        val issuer = if (key.collection == "configuration") entity ?: nodeId else nodeId
        if (document.string("iss")?.let { it != issuer } == true) invalid("iss conflicts with node identifier", "/iss")
        val subject = when(key.collection) { "configuration" -> issuer; "subordinate" -> key.resourceId; else -> key.subjectId }
        if (document.string("sub")?.let { it != subject } == true) invalid("sub conflicts with entity_id", "/sub")
        document["lifetime"]?.let { if ((it as? JsonPrimitive)?.longOrNull?.let { n -> n > 0 && n <= 9_007_199_254_740_991L - now() } != true) invalid("lifetime must be a positive number of seconds", "/lifetime") }
        if (key.collection == "configuration") {
            val federation = ((document["metadata"] as? JsonObject)?.get("federation_entity") as? JsonObject) ?: JsonObject(emptyMap())
            val fetch = federation.containsKey("federation_fetch_endpoint")
            val list = federation.containsKey("federation_list_endpoint")
            if (fetch != list) invalid("Superior must publish both fetch and list endpoints", "/metadata/federation_entity")
            val hints = document["authority_hints"] as? JsonArray
            if (!fetch && hints.isNullOrEmpty()) invalid("Leaf nodes require nonempty authority_hints", "/authority_hints")
            if (!hints.isNullOrEmpty() && (document.containsKey("trust_mark_issuers") || document.containsKey("trust_mark_owners"))) invalid("Trust Mark recognition claims require a Trust Anchor")
        }
        if (key.collection == "subordinate" && document.string("status") == "active") {
            val jwks = (document["jwks"] as? JsonObject)?.get("keys") as? JsonArray
            if (jwks.isNullOrEmpty() || jwks.any { it !is JsonObject || !hasWellFormedPublicJwkMaterial(it) }) invalid("Active subordinate requires well-formed public JWKS", "/jwks")
        }
        if (key.collection == "trust-mark-type" && runCatching { java.net.URI(key.resourceId).isAbsolute }.getOrDefault(false) != true)
            invalid("trust_mark_type must be a collision-resistant URI", "/trust_mark_type")
        if (key.collection == "trust-mark-type" && document.containsKey("delegation")) {
            val delegation = document.string("delegation") ?: invalid("delegation must be a compact JWT", "/delegation")
            val issuer = identifier(scope) ?: adminFail("not_initialized", 409, "Node identifier is not configured")
            val anchors = config.getFederationConfig().endpointAuthTrustAnchors.ifEmpty { listOf(issuer) }
            var valid = false
            for (anchor in anchors) {
                val configuration = if (anchor == issuer) store.read(scope, AdminDocumentKey("configuration"))?.artifact?.let {
                    runCatching { json.decodeFromJsonElement(EntityConfigurationStatement.serializer(), payload(it)) }.getOrNull()
                } else fetchConfiguration.execute(GetEntityConfigurationArgs(anchor)).let { if (it.isOk) it.value else null }
                if (configuration != null) {
                    val result = verifyDelegation.execute(VerifyTrustMarkDelegationArgs(delegation, key.resourceId, issuer, configuration, now()))
                    if (result.isOk && result.value.isValid) { valid = true; break }
                }
            }
            if (!valid) invalid("Delegation does not verify against a trusted owner for this issuer and type", "/delegation")
        }
        if (key.collection == "configuration" || key.collection == "subordinate") {
            val current = now()
            val sample = claims(scope, key, document, current)
            // Pending subjects may omit keys. Validate their supplied claims using a local public-key placeholder only for structural validation.
            val validationClaims = if (key.collection == "subordinate" && document.string("status") != "active" && !sample.containsKey("jwks"))
                JsonObject(sample + ("jwks" to publicJwks(scope))) else sample
            val validation = EntityStatementValidation.validateStructure(Jwt(
                JwtHeader(typ = "entity-statement+jwt", kid = "administration-validation", alg = "ES256"), validationClaims, ""), current, if (key.collection == "configuration") 0 else 1)
            if (!validation.ok) invalid(validation.reason ?: "Invalid Federation claims")
            StatementSigningValidation.producerRefusal(validationClaims, isEntityConfiguration = key.collection == "configuration")?.let { invalid(it) }
            val marks = (document["trust_marks"] as? JsonArray).orEmpty()
            for (entry in marks) {
                val compact = (entry as? JsonObject)?.string("trust_mark") ?: invalid("Held Trust Mark must contain a compact JWT", "/trust_marks")
                val anchors = config.getFederationConfig().endpointAuthTrustAnchors.ifEmpty { listOfNotNull(issuer) }
                var accepted = false
                for (anchor in anchors) {
                    val configuration = if (anchor == issuer && key.collection == "configuration") runCatching {
                        json.decodeFromJsonElement(EntityConfigurationStatement.serializer(), validationClaims)
                    }.getOrNull() else if (anchor == identifier(scope)) store.read(scope, AdminDocumentKey("configuration"))?.artifact?.let {
                        runCatching { json.decodeFromJsonElement(EntityConfigurationStatement.serializer(), payload(it)) }.getOrNull()
                    } else fetchConfiguration.execute(GetEntityConfigurationArgs(anchor)).let { if (it.isOk) it.value else null }
                    if (configuration != null) {
                        val checked = verifyMark.execute(VerifyTrustMarkArgs(compact, configuration, current, subject))
                        if (checked.isOk && checked.value.isValid) { accepted = true; break }
                    }
                }
                if (!accepted) invalid("Held Trust Mark is not valid for this subject under a trusted anchor", "/trust_marks")
            }
        }
    }

    override suspend fun verifySubject(scope: AdminNodeScope, key: AdminDocumentKey, document: JsonObject) {
        val subject = if (key.collection == "subordinate") key.resourceId else key.subjectId
        if (key.collection == "trust-mark-grant") {
            val anchors = config.getFederationConfig().endpointAuthTrustAnchors.ifEmpty { listOfNotNull(identifier(scope)) }
            for (anchor in anchors) {
                if (verifyTrust.execute(VerifyEntityTrustArgs(subject, RegistrationTrustAnchor(anchor))).isOk) return
            }
            adminFail("subject_mismatch", 422, "Subject has no verified chain to a trusted Trust Anchor")
        }
        val remote = fetchConfiguration.execute(GetEntityConfigurationArgs(subject))
        if (remote.isErr) adminFail("subject_mismatch", 422, "Subject Entity Configuration could not be fetched and verified")
        val value = json.encodeToJsonElement(EntityConfigurationStatement.serializer(), remote.value).jsonObject
        if (!(value["authority_hints"] as? JsonArray).orEmpty().contains(JsonPrimitive(identifier(scope)))) adminFail("subject_mismatch", 422, "Subject does not name this node in authority_hints")
        val expected = (document["jwks"] as? JsonObject)?.get("keys") as? JsonArray
        val actual = (value["jwks"] as? JsonObject)?.get("keys") as? JsonArray
        if (expected == null || actual == null || expected.toSet() != actual.toSet()) adminFail("subject_mismatch", 422, "Subject Federation Entity Keys do not match supplied jwks")
        val constraints = document["constraints"] as? JsonObject
        val naming = constraints?.get("naming_constraints") as? JsonObject
        fun names(name: String) = (naming?.get(name) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
        if (!NamingConstraintsMatcher.isAllowed(subject, names("permitted"), names("excluded"))) adminFail("subject_mismatch", 422, "Subject violates naming_constraints")
        val allowed = (constraints?.get("allowed_entity_types") as? JsonArray)?.map { it.jsonPrimitive.content }
        val types = (value["metadata"] as? JsonObject)?.keys.orEmpty()
        if (allowed != null && types.any { !EntityStatementValidation.isEntityTypeAllowed(it, allowed) }) adminFail("subject_mismatch", 422, "Subject violates allowed_entity_types")
    }

    private suspend fun claims(scope: AdminNodeScope, key: AdminDocumentKey, document: JsonObject, time: Long, publicKeys: JsonObject? = null): JsonObject {
        val issuer = (if (key.collection == "configuration") document.string("entity_id") ?: identifier(scope) else identifier(scope))
            ?: adminFail("not_initialized", 409, "Node Entity Identifier is not configured")
        val issuance = if (key.collection == "trust-mark-grant") store.read(scope, AdminDocumentKey("trust-mark-type", key.resourceId))?.document else null
        val lifetime = (document["lifetime"] as? JsonPrimitive)?.longOrNull ?: (issuance?.get("lifetime") as? JsonPrimitive)?.longOrNull ?: config.getFederationConfig().statementLifetimeSeconds
            ?: adminFail("not_initialized", 409, "No statement lifetime is configured")
        return buildJsonObject {
            issuance?.let { adminSignedClaims(it).forEach { (k,v) -> put(k,v) } }
            adminSignedClaims(document).filterKeys { !(it == "authority_hints" && (document[it] as? JsonArray)?.isEmpty() == true) }.forEach { (k,v) -> put(k,v) }
            if (key.collection == "configuration") {
                put("jwks", publicKeys ?: publicJwks(scope))
            }
            put("iss", issuer); put("sub", if (key.collection == "configuration") issuer else if (key.collection == "subordinate") key.resourceId else key.subjectId)
            put("iat", time); put("exp", time + lifetime)
        }
    }
    private fun publicJwks(scope: AdminNodeScope): JsonObject = buildJsonObject {
        put("keys", JsonArray(keys(scope).filter { it.revoked_at == null && (it.expires_at == null || it.expires_at!!.toEpochSecond(java.time.ZoneOffset.UTC) > now()) }.map { JsonObject(Json.parseToJsonElement(it.key).jsonObject.filterKeys { member -> member !in PRIVATE_JWK_MEMBERS }) }))
    }
    override suspend fun prepare(scope: AdminNodeScope, key: AdminDocumentKey, document: JsonObject): AdminPreparedArtifact {
        val fence = keyState(scope.accountId)
        val signer = selected(scope) ?: adminFail("not_initialized", 409, "No signing-capable Federation Entity Key is available")
        return prepareWith(scope, key, document, signer, null, fence)
    }
    private suspend fun prepareWith(scope: AdminNodeScope, key: AdminDocumentKey, document: JsonObject, signer: Jwk, publicKeys: JsonObject?, fence: String): AdminPreparedArtifact {
        val payload = claims(scope, key, document, now(), publicKeys)
        val signingKid = signer.kid ?: adminFail("not_initialized", 409, "Selected signing key has no kid")
        val signingAlgorithm = signer.alg ?: Json.parseToJsonElement(signer.key).jsonObject.string("alg")
            ?: adminFail("not_initialized", 409, "Selected signing key has no recorded algorithm")
        val header = buildJsonObject {
            put("typ", if (key.collection == "trust-mark-grant") "trust-mark+jwt" else "entity-statement+jwt")
            put("kid", signingKid); put("alg", signingAlgorithm)
        }
        val descriptor = AdminPreparedKey(signer.id, JsonObject(Json.parseToJsonElement(signer.key).jsonObject.filterKeys { it !in PRIVATE_JWK_MEMBERS }),
            signingKid, signingAlgorithm, signer.kms, signer.kms_key_ref)
        val result = materials.sign(scope, descriptor, header, payload)
        val parts = result.split('.')
        if (parts.size != 3 || runCatching {
            Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[0]))).jsonObject == header &&
                Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]))).jsonObject == payload
        }.getOrDefault(false) != true) adminFail("internal", 502, "Signer changed the validated federation statement")
        val verified = jwt.verifyJws(VerifyJwsArgs(jws = JwsCompact(result), trustedJwks = buildJsonObject {
            put("keys", JsonArray(listOf(descriptor.publicJwk)))
        }))
        if (verified.isErr || !verified.value.isValid) adminFail("internal", 502, "Federation signature does not match the authorized public key")
        if (keyState(scope.accountId) != fence) adminFail("precondition_failed", 412, "Signing state changed while preparing the artifact")
        return AdminPreparedArtifact(result, fence)
    }

    override suspend fun selectSigningKey(scope: AdminNodeScope, keyId: String?, expectedRevision: Long): AdminCommandResult {
        val fence = keyState(scope.accountId)
        val selection = selectionQueries.findByAccountId(scope.accountId).executeAsOneOrNull()
        if (expectedRevision < 0 || (selection?.revision ?: 0L) != expectedRevision) adminFail("precondition_failed", 412, "Signing selection revision does not match")
        val states = store.list(scope, "key-state").associateBy { it.key.resourceId }
        val candidates = keys(scope).filter { capable(it) && states[it.kid]?.document?.string("status") != "retiring" }
        val signer = if (keyId == null) candidates.firstOrNull() else candidates.find { it.id == keyId }
        if (signer == null) adminFail("conflict", 409, "Selected key must be active and signing-capable for this node")
        val configuration = store.read(scope, AdminDocumentKey("configuration")) ?: adminFail("not_found", 404, "Configuration not found")
        val artifact = if (configuration.artifact != null) prepareWith(scope, configuration.key, configuration.document, signer, null, fence).artifact else null
        val mutation = AdminMutation(configuration.key, configuration.etag, configuration.document, artifact, expectedKeyState = fence,
            keyChange = AdminKeyChange(selectedKeyId = signer.id, updateSelection = true))
        val committed = store.commit(scope, listOf(mutation))?.firstOrNull() ?: adminFail("precondition_failed", 412, "Signing selection changed during preparation")
        return AdminCommandResult(document = committed.document, etag = committed.etag, artifact = committed.artifact)
    }

    override suspend fun keys(operation: AdminOperation, args: AdminCommandArgs): AdminCommandResult {
        val scope = args.scope
        val initialFence = keyState(scope.accountId)
        val all = keys(scope)
        val states = store.list(scope, "key-state").associateBy { it.key.resourceId }
        val signer = selected(scope)
        fun etag(kid: String) = adminKeyEtag(initialFence, kid)
        fun document(row: Jwk): JsonObject = buildJsonObject {
            val public = JsonObject(Json.parseToJsonElement(row.key).jsonObject.filterKeys { it !in PRIVATE_JWK_MEMBERS })
            put("kid", row.kid ?: row.id); put("public_jwk", public)
            val state = documentQueries.find(scope.accountId, "key-state", row.kid ?: row.id, "").executeAsOneOrNull()?.let { Json.parseToJsonElement(it.document).jsonObject }
            put("status", if (row.revoked_at != null || row.expires_at?.toEpochSecond(java.time.ZoneOffset.UTC)?.let { it <= now() } == true) "revoked" else state?.string("status") ?: "active")
            put("signing", row.id == selected(scope)?.id); row.alg?.let { put("alg", it) }; public["kty"]?.let { put("kty", it) }
            row.created_at?.let { put("created_at", it.toEpochSecond(java.time.ZoneOffset.UTC)) }
            row.expires_at?.let { put("not_after", it.toEpochSecond(java.time.ZoneOffset.UTC)) }
        }
        if (operation == AdminOperation.ListAdminKeys) {
            val limit = args.query["limit"]?.let { it.toIntOrNull() ?: adminFail("invalid_request", 400, "Invalid limit") } ?: 100
            if (limit <= 0) adminFail("invalid_request", 400, "limit must be positive")
            val offset = args.query["cursor"]?.let { it.removePrefix("page:").toIntOrNull() ?: adminFail("invalid_request", 400, "Invalid cursor") } ?: 0
            if (offset < 0) adminFail("invalid_request", 400, "Invalid cursor")
            val docs = all.map(::document).filter { args.query["status"]?.let { status -> it.string("status") == status } ?: (it.string("status") != "revoked") }
            val items = docs.drop(offset).take(limit.coerceAtMost(1000))
            return AdminCommandResult(document = buildJsonObject { put("items", JsonArray(items)); if (offset + items.size < docs.size) put("next", "page:${offset + items.size}") })
        }
        val row = if (operation != AdminOperation.CreateAdminKey) all.find { it.kid == args.resourceId }
            ?: adminFail("not_found", 404, "Key not found") else null
        if (operation == AdminOperation.ReadAdminKey) return AdminCommandResult(document = document(row!!), etag = etag(row.kid!!))
        if (!adminIfMatch(args.ifMatch, row?.kid?.let(::etag))) adminFail("precondition_failed", 412, "Key revision does not match If-Match")
        if (operation == AdminOperation.DeleteAdminKey && row?.revoked_at != null) return AdminCommandResult(status = 204)
        val configuration = store.read(scope, AdminDocumentKey("configuration")) ?: adminFail("not_found", 404, "Configuration not found")
        var preparationId: String? = null
        var committed = false
        try {
        var staged: AdminPreparedKey? = null
        var created: Jwk? = null
        var nextSigner = signer
        var revoke: String? = null
        val additional = mutableListOf<AdminMutation>()
        if (operation == AdminOperation.DeleteAdminKey) {
            if (capable(row!!) && all.count(::capable) <= 1) adminFail("last_signing_key", 409, "The last signing-capable key cannot be deleted")
            revoke = row.id
            if (signer?.id == row.id) nextSigner = all.firstOrNull { it.id != row.id && capable(it) && states[it.kid]?.document?.string("status") != "retiring" }
                ?: adminFail("last_signing_key", 409, "No active replacement signer is available")
        } else {
            val body = args.document ?: adminFail("invalid_request", 400, "A key request is required")
            val generate = body["generate"] as? JsonObject
            val imported = body["import"] as? JsonObject
            if ((generate == null) == (imported == null)) adminFail("invalid_request", 400, "Exactly one of generate and import is required")
            val delay = body["switch_after"]?.let { (it as? JsonPrimitive)?.longOrNull ?: adminFail("invalid_request", 400, "Invalid switch_after") } ?: 0L
            if (delay < 0 || delay > 9_007_199_254_740_991L - now()) adminFail("invalid_request", 400, "Invalid switch_after")
            if (operation == AdminOperation.RotateAdminKey && row?.id != signer?.id)
                adminFail("conflict", 409, "Rollover requires the current signing key")
            val id = java.util.UUID.randomUUID().toString()
            staged = if (generate != null) {
                val algorithmName = generate.string("alg") ?: "ES256"
                val expected = when (algorithmName) { "ES256" -> "EC" to "P-256"; "ES384" -> "EC" to "P-384"; "ES512" -> "EC" to "P-521"; "EdDSA" -> "OKP" to "Ed25519"; else -> if (algorithmName.startsWith("RS") || algorithmName.startsWith("PS")) "RSA" to null else adminFail("invalid_request", 400, "Unsupported algorithm") }
                if (generate.string("kty")?.let { it != expected.first } == true || generate.string("crv")?.let { it != expected.second } == true) adminFail("invalid_request", 400, "generate key type or curve conflicts with alg")
                SignatureAlgorithm.fromJose(JwaAlgorithm.fromValue(algorithmName) ?: adminFail("invalid_request", 400, "Unsupported algorithm"))
                preparationId = id
                beginKeyPreparation(scope, configuration, id, algorithmName)
                val generated = materials.prepare(scope, id, algorithmName)
                if (generated.id != id || generated.algorithm != algorithmName || generated.kid.isBlank() ||
                    generated.kms.isBlank() || generated.kmsKeyRef.isBlank() || generated.publicJwk.string("kid") != generated.kid ||
                    generated.publicJwk.keys.any { it in PRIVATE_JWK_MEMBERS } || !hasWellFormedPublicJwkMaterial(generated.publicJwk) ||
                    generated.publicJwk.string("kty") != expected.first || expected.second?.let { it != generated.publicJwk.string("crv") } == true)
                    adminFail("internal", 502, "Key authority returned an incompatible public receipt")
                bindKeyPreparation(scope, generated)
                generated
            } else {
                if (imported!!.keys.any { it in PRIVATE_JWK_MEMBERS } || !hasWellFormedPublicJwkMaterial(imported)) adminFail("invalid_request", 400, "Import requires a public asymmetric JWK")
                val kid = imported.string("kid") ?: thumbprint(imported)
                val known = all.firstOrNull { existing -> capable(existing) && thumbprint(Json.parseToJsonElement(existing.key).jsonObject) == thumbprint(imported) }
                if (operation == AdminOperation.RotateAdminKey && known == null)
                    adminFail("conflict", 409, "Imported rollover key has no signing route authorized for this node")
                val knownAlgorithm = known?.alg ?: known?.let { Json.parseToJsonElement(it.key).jsonObject.string("alg") }
                if (known != null && imported.string("alg")?.let { it != knownAlgorithm } == true)
                    adminFail("invalid_request", 400, "Imported key algorithm conflicts with its authorized signing route")
                val derived = AdminPreparedKey(id, JsonObject(imported + ("kid" to JsonPrimitive(kid))), kid, imported.string("alg") ?: knownAlgorithm, known?.kms ?: "", known?.kms_key_ref ?: "")
                if (known == null) derived else {
                    val algorithm = derived.algorithm ?: adminFail("conflict", 409, "Existing signing route has no algorithm")
                    preparationId = id
                    beginKeyPreparation(scope, configuration, id, algorithm, generated = false, sourceKeyId = known.id)
                    val prepared = materials.prepareExisting(scope, derived, known.id)
                    if (prepared != derived) adminFail("internal", 502, "Existing key authority changed the derived signing descriptor")
                    bindKeyPreparation(scope, prepared)
                    prepared
                }
            }
            if (all.any { it.kid == staged.kid }) adminFail("already_exists", 409, "Key identifier already exists")
            created = Jwk(staged.id, scope.accountId, staged.algorithm, staged.kid, staged.kms, staged.kmsKeyRef, staged.publicJwk.toString(), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC), null, null, null, null)
            if (operation == AdminOperation.RotateAdminKey) {
                if (delay == 0L) nextSigner = created
                val stateKey = AdminDocumentKey("key-state", row!!.kid!!)
                val previous = store.read(scope, stateKey)
                val state = buildJsonObject { put("status", "retiring"); put("new_kid", created.kid!!); put("switch_at", now() + delay); put("switched", delay == 0L) }
                additional += AdminMutation(stateKey, previous?.etag, state)
            } else if (signer == null && capable(created)) nextSigner = created
        }
        val desiredKeys = (all.filter { it.revoked_at == null && it.id != revoke && (it.expires_at == null || it.expires_at!!.toEpochSecond(java.time.ZoneOffset.UTC) > now()) } + listOfNotNull(created))
        val public = buildJsonObject { put("keys", JsonArray(desiredKeys.map { JsonObject(Json.parseToJsonElement(it.key).jsonObject.filterKeys { member -> member !in PRIVATE_JWK_MEMBERS }) })) }
        val artifact = if (configuration.artifact != null && nextSigner != null)
            prepareWith(scope, configuration.key, configuration.document, nextSigner, public, initialFence).artifact else configuration.artifact
        val keyChange = AdminKeyChange(newKey = staged, selectedKeyId = nextSigner?.id, updateSelection = nextSigner?.id != signer?.id || selectionQueries.findByAccountId(scope.accountId).executeAsOneOrNull()?.jwk_id == null && nextSigner != null, revokeKeyId = revoke)
        val mutation = AdminMutation(configuration.key, configuration.etag, configuration.document, artifact, expectedKeyState = initialFence, keyChange = keyChange)
        val preparation = preparationId?.let { keyId ->
            val pending = store.readCommitted(scope, adminKeyPreparationKey(keyId))
                ?: adminFail("precondition_failed", 412, "Key preparation disappeared")
            if (pending.document.string("status") != "PENDING" ||
                pending.document.string("configuration_revision") != configuration.etag ||
                pending.document.getValue("lease_expires_at").jsonPrimitive.long <= now())
                adminFail("precondition_failed", 412, "Key preparation is no longer current")
            val incarnation = store.readCommitted(scope, AdminDocumentKey("node-incarnation"))
                ?: adminFail("precondition_failed", 412, "Node incarnation disappeared")
            if (incarnation.document.string("id") != pending.document.string("node_incarnation"))
                adminFail("precondition_failed", 412, "Node incarnation changed")
            AdminMutation(pending.key, pending.etag, JsonObject(pending.document + ("status" to JsonPrimitive("COMMITTED"))),
                expectedDependencies = mapOf(incarnation.key to incarnation.etag))
        }
        if (store.commit(scope, listOf(mutation) + additional + listOfNotNull(preparation)) == null) adminFail("precondition_failed", 412, "Key or configuration changed during preparation")
        committed = true
        if (operation == AdminOperation.DeleteAdminKey) return AdminCommandResult(status = 204)
        val persisted = keys(scope).single { it.id == created!!.id }
        return AdminCommandResult(document = document(persisted), etag = adminKeyEtag(keyState(scope.accountId), persisted.kid!!), status = 201, location = "${scope.basePath}/keys/${encodeAdminSegment(persisted.kid!!)}")
        } finally {
            if (!committed && preparationId != null) withContext(NonCancellable) {
                // The failed publication stays failed even if cleanup has to be retried after restart.
                runCatching { abortKeyPreparation(scope, preparationId!!) }
            }
        }
    }

    private suspend fun beginKeyPreparation(scope: AdminNodeScope, configuration: AdminStoredDocument, keyId: String, algorithm: String, generated: Boolean = true, sourceKeyId: String? = null) {
        val incarnation = adminNodeIncarnation(store, scope)
        val document = buildJsonObject {
            put("status", "PENDING"); put("generated", generated)
            sourceKeyId?.let { put("source_key_id", it) }
            put("account_id", scope.accountId); scope.entityId?.let { put("entity_identifier", it) }
            put("key_id", keyId); put("preparation_id", java.util.UUID.randomUUID().toString())
            put("node_incarnation", incarnation); put("configuration_revision", configuration.etag)
            put("algorithm", algorithm); put("lease_expires_at", now() + 300)
        }
        if (store.commit(scope, listOf(AdminMutation(adminKeyPreparationKey(keyId), null, document))) == null)
            adminFail("precondition_failed", 412, "Key preparation already exists")
    }
    private suspend fun bindKeyPreparation(scope: AdminNodeScope, key: AdminPreparedKey) {
        val before = store.readCommitted(scope, adminKeyPreparationKey(key.id))
            ?: adminFail("precondition_failed", 412, "Key preparation disappeared")
        if (before.document.string("status") != "PENDING") adminFail("precondition_failed", 412, "Key preparation was aborted")
        val document = buildJsonObject {
            before.document.forEach { (name, value) -> put(name, value) }
            put("kms", key.kms); put("kms_key_ref", key.kmsKeyRef); put("kid", key.kid); put("public_jwk", key.publicJwk)
        }
        if (store.commit(scope, listOf(AdminMutation(before.key, before.etag, document))) == null)
            adminFail("precondition_failed", 412, "Key preparation changed")
    }
    private suspend fun abortKeyPreparation(scope: AdminNodeScope, keyId: String) {
        val key = adminKeyPreparationKey(keyId)
        var preparation = store.readCommitted(scope, key) ?: return
        if (preparation.document.string("status") == "COMMITTED") return
        if (preparation.document.string("status") == "PENDING") {
            preparation = store.commit(scope, listOf(AdminMutation(key, preparation.etag,
                JsonObject(preparation.document + ("status" to JsonPrimitive("ABORTED"))))))?.single() ?: return
        }
        if (preparation.document.string("status") != "ABORTED" || preparation.document["cleanup_complete"] == JsonPrimitive(true)) return
        if (preparation.document["generated"] == JsonPrimitive(false) || materials.discard(scope, keyId)) store.commit(scope, listOf(AdminMutation(key, preparation.etag,
            JsonObject(preparation.document + ("cleanup_complete" to JsonPrimitive(true))))))
    }
    override suspend fun recoverKeyPreparations(scope: AdminNodeScope, abortPending: Boolean) {
        for (preparation in store.recoverableKeyPreparations(scope, 64)) {
            try {
                if (abortPending || preparation.document.string("status") == "ABORTED" ||
                    (preparation.document["lease_expires_at"] as? JsonPrimitive)?.longOrNull?.let { it <= now() } == true)
                    abortKeyPreparation(scope, preparation.key.resourceId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Durable ABORTED/PENDING state is retried by independent maintenance. */ }
            finally { store.touchKeyPreparation(scope, preparation.key.resourceId) }
        }
    }
    private fun thumbprint(jwk: JsonObject): String {
        val names = when(jwk.string("kty")) { "EC" -> listOf("crv", "kty", "x", "y"); "RSA" -> listOf("e", "kty", "n"); "OKP" -> listOf("crv", "kty", "x"); else -> adminFail("invalid_request", 400, "Unsupported public key type") }
        val canonical = JsonObject(names.associateWith { jwk[it] ?: adminFail("invalid_request", 400, "Incomplete public key") }).toString()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
    }
    private fun <T> IdkResult<T, FederationError>.requireValue(): T {
        if (isErr) adminFail(if (error.httpStatusValue >= 500) "internal" else "invalid_federation_claim", error.httpStatusValue, error.message.defaultMessage)
        return value
    }
    private fun invalid(detail: String, pointer: String? = null): Nothing = adminFail("invalid_federation_claim", 400, detail, pointer)
    private fun now() = clock()
    companion object { private val PRIVATE_JWK_MEMBERS = setOf("d", "p", "q", "dp", "dq", "qi", "oth", "k", "dP", "dQ", "qInv") }
}
