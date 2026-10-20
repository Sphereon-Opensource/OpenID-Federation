package com.sphereon.openid.fed.admin

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class AdminWorkflowTest {
    @Test fun signedClaimProjectionRetainsExtensionsAndCriticalPolicyDeclarations() {
        val document = Json.parseToJsonElement("""{"entity_id":"https://node.example","status":"active","lifetime":60,"etag":"opaque","custom_claim":{"nested":true},"metadata_policy_crit":["custom_operator"],"jwks":{"keys":[]},"metadata":{"federation_entity":{}}}""").jsonObject
        val claims = adminSignedClaims(document)
        assertEquals(document["custom_claim"], claims["custom_claim"])
        assertEquals(document["metadata_policy_crit"], claims["metadata_policy_crit"])
        assertEquals(document["jwks"], claims["jwks"])
        assertFalse(claims.containsKey("entity_id")); assertFalse(claims.containsKey("status"))
        assertFalse(claims.containsKey("lifetime")); assertFalse(claims.containsKey("etag"))
    }
    @Test fun conditionalHeadersUseStrongComparisonAndLists() {
        assertTrue(adminIfMatch(null, null))
        assertTrue(adminIfMatch("*", "\"current\"")); assertFalse(adminIfMatch("*", null))
        assertTrue(adminIfMatch("\"other\", W/\"weak\", \"current\"", "\"current\""))
        assertTrue(adminIfMatch("\"contains,comma\", \"other\"", "\"contains,comma\""))
        assertFalse(adminIfMatch("W/\"current\"", "\"current\""))
        assertFalse(adminIfMatch("\"other\", \"missing\"", "\"current\""))
        assertFailsWith<AdminFailure> { adminIfMatch("unquoted", "\"current\"") }
    }
    @Test fun keyRevisionsEncodeOpaqueKidsIntoValidStrongTags() {
        val tag = adminKeyEtag("state", "key\"雪,/%2F")
        assertEquals(2, tag.count { it == '"' })
        assertTrue(tag.all { it.code in 33..126 })
        assertTrue(adminIfMatch(tag, tag))
        assertFalse(adminIfMatch("W/$tag", tag))
    }
    private val scope = AdminNodeScope("account", "https://ta.example")
    private fun doc(text: String) = Json.parseToJsonElement(text).jsonObject
    private class MemoryStore : AdminDocumentStore {
        val rows = mutableMapOf<AdminDocumentKey, AdminStoredDocument>()
        var revision = 0
        var keyState = "keys"
        var beforeCommit: (() -> Unit)? = null
        val candidates = mutableMapOf<String, AdminMutation>()
        val completed = mutableMapOf<String, AdminStoredDocument>()
        override suspend fun stage(scope: AdminNodeScope, mutation: AdminMutation): AdminStagedPublication {
            val digest = "sha256:" + mutation.artifact!!.hashCode().toString()
            candidates[digest] = mutation
            return AdminStagedPublication(digest, mutation.key.collection, mutation.key.resourceId, mutation.key.subjectId, mutation.artifact!!, mutation.activationContext)
        }
        override suspend fun pendingStages(scope: AdminNodeScope) = candidates.map { (digest, mutation) ->
            AdminStagedPublication(digest, mutation.key.collection, mutation.key.resourceId, mutation.key.subjectId, mutation.artifact!!, mutation.activationContext)
        }
        override suspend fun finalizeStage(scope: AdminNodeScope, digest: String, expectedStageEtag: String?): AdminStoredDocument? {
            completed[digest]?.let { return it }
            val mutation = candidates[digest] ?: adminFail("not_found", 404, "Stage missing")
            val row = commit(scope, listOf(mutation))?.firstOrNull() ?: return null
            candidates.remove(digest); completed[digest] = row; return row
        }
        override suspend fun adopt(scope: AdminNodeScope, documents: List<AdminStoredDocument>): Boolean {
            if (rows.containsKey(AdminDocumentKey("configuration"))) return false
            documents.forEach { rows[it.key] = it }; return true
        }
        override suspend fun readCommitted(scope: AdminNodeScope, key: AdminDocumentKey) = rows[key]
        override suspend fun listCommitted(scope: AdminNodeScope, collection: String, resourceId: String?) = rows.values.filter { it.key.collection == collection && (resourceId == null || it.key.resourceId == resourceId) }
        override suspend fun read(scope: AdminNodeScope, key: AdminDocumentKey) = readCommitted(scope, key)
        override suspend fun list(scope: AdminNodeScope, collection: String, resourceId: String?) = listCommitted(scope, collection, resourceId)
        override suspend fun commit(scope: AdminNodeScope, mutations: List<AdminMutation>): List<AdminStoredDocument>? {
            beforeCommit?.let { hook -> beforeCommit = null; hook() }
            if (mutations.any { it.expectedKeyState?.let { expected -> expected != keyState } == true ||
                    it.expectedDependencies.any { (key, etag) -> rows[key]?.etag != etag } }) return null
            val revokedTypes = mutations.filter { it.key.collection == "trust-mark-type" && it.document.string("status") == "revoked" }.map { it.key.resourceId }.toSet()
            val writes = mutations.filterNot { it.key.collection == "trust-mark-grant" && it.key.resourceId in revokedTypes }.toMutableList()
            rows.values.filter { it.key.collection == "trust-mark-grant" && it.key.resourceId in revokedTypes }.forEach { row ->
                writes += AdminMutation(row.key, row.etag, JsonObject(row.document + ("status" to JsonPrimitive("revoked"))))
            }
            if (writes.any { rows[it.key]?.etag != it.expectedEtag }) return null
            val limit = scope.subordinateLimit
            if (limit != null) {
                val current = rows.values.count { it.key.collection == "subordinate" && it.document.string("status") != "revoked" }
                val delta = writes.filter { it.key.collection == "subordinate" }.sumOf { mutation ->
                    val old = rows[mutation.key]
                    (if (mutation.document.string("status") != "revoked") 1 else 0) - (if (old != null && old.document.string("status") != "revoked") 1 else 0)
                }
                if (delta > 0 && current.toLong() + delta > limit) adminFail("conflict", 409, "Subordinate limit reached")
            }
            val acknowledgements = writes.map { rows[adminLifetimeAcknowledgementKey(it.key)]?.let { row -> decodeAdminLifetimeAcknowledgement(row.document) } }
            writes.indices.forEach { index -> checkAdminLifetimePolicy(writes[index].key, writes[index].lifetimePolicy,
                acknowledgements[index], rows[writes[index].key]?.etag, writes[index].recreate) }
            return writes.mapIndexed { index, m -> AdminStoredDocument(m.key, m.document, "\"${++revision}\"", revision.toLong(), m.artifact).also { row ->
                rows[m.key] = row
                if (adminLifetimeKind(m.key) != null) {
                    val key = adminLifetimeAcknowledgementKey(m.key)
                    val ack = updatedAdminLifetimeAcknowledgement(m, acknowledgements[index], row.etag, "incarnation-$revision")
                    if (ack == null) rows.remove(key) else rows[key] = AdminStoredDocument(key, Json.encodeToJsonElement(ack).jsonObject, row.etag, row.updatedAt)
                }
            } }
        }

    }
    private class Protocol : AdminProtocolPort {
        var roles = listOf("trust_anchor", "trust_mark_issuer")
        var verificationFails = false
        var verifications = 0
        var preparations = 0
        var policyContext: AdminLifetimePolicyContext? = null
        override suspend fun lifetimePolicy(scope: AdminNodeScope, key: AdminDocumentKey) = policyContext
        override suspend fun node(scope: AdminNodeScope) = buildJsonObject { put("roles", JsonArray(roles.map(::JsonPrimitive))) }
        override suspend fun keys(operation: AdminOperation, args: AdminCommandArgs) = AdminCommandResult()
        override suspend fun validate(scope: AdminNodeScope, key: AdminDocumentKey, document: JsonObject) {}
        override suspend fun verifySubject(scope: AdminNodeScope, key: AdminDocumentKey, document: JsonObject) {
            verifications++
            if (verificationFails) adminFail("subject_mismatch", 422, "Subject unavailable")
        }
        override suspend fun prepare(scope: AdminNodeScope, key: AdminDocumentKey, document: JsonObject): AdminPreparedArtifact {
            preparations++
            return AdminPreparedArtifact("header." + kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT).encode("{\"iat\":1,\"exp\":9999999999}".encodeToByteArray()) + ".signature$preparations", "keys")
        }
    }
    private val store = MemoryStore()
    private val protocol = Protocol()
    private val workflow = DefaultAdminWorkflow(store, protocol)
    private val subordinate = "https://op.example/a?b=c"
    private suspend fun create(status: String = "pending") = workflow.execute(AdminOperation.CreateAdminSubordinate,
        AdminCommandArgs(scope, document = doc("""{"entity_id":"$subordinate","status":"$status","jwks":{"keys":[]},"extension":{"kept":true}}""")))

    @Test fun mergePatchRecursesRemovesAndReplacesArrays() {
        assertEquals(doc("""{"metadata":{"a":2},"array":[3],"extension":true}"""), mergeAdminPatch(
            doc("""{"metadata":{"a":1,"b":1},"array":[1,2],"extension":true}"""),
            doc("""{"metadata":{"a":2,"b":null},"array":[3]}""")))
    }
    @Test fun pendingCreateNeverVerifiesOrPublishes() = runTest {
        val result = create()
        assertTrue(result.isOk); assertEquals(201, result.value.status)
        assertEquals(0, protocol.verifications); assertEquals(0, protocol.preparations)
        assertNull(store.rows.values.single().artifact)
        assertEquals("/admin/v1/subordinates/https%3A%2F%2Fop.example%2Fa%3Fb%3Dc", result.value.location)
    }
    @Test fun activationVerifiesAndFailedCheckLeavesOldRevisionUntouched() = runTest {
        val created = create().value
        protocol.verificationFails = true
        val rejected = workflow.execute(AdminOperation.SetAdminSubordinateStatus, AdminCommandArgs(scope, subordinate, document = doc("""{"status":"active"}"""), ifMatch = created.etag))
        assertTrue(rejected.isErr); assertEquals("subject_mismatch", rejected.error.token)
        assertEquals(created.etag, store.rows.values.single().etag)
        assertEquals("pending", store.rows.values.single().document["status"]?.jsonPrimitive?.content)
    }
    @Test fun staleEtagDoesNotVerifyOrWrite() = runTest {
        create()
        val result = workflow.execute(AdminOperation.PatchAdminSubordinate, AdminCommandArgs(scope, subordinate, document = doc("""{"status":"active"}"""), ifMatch = "\"stale\""))
        assertTrue(result.isErr); assertEquals(412, result.error.status); assertEquals(0, protocol.verifications)
        assertEquals(1, store.revision)
    }
    @Test fun suspendedResourceStopsServingArtifact() = runTest {
        create("active")
        workflow.execute(AdminOperation.SetAdminSubordinateStatus, AdminCommandArgs(scope, subordinate, document = doc("""{"status":"suspended"}""")))
        val result = workflow.execute(AdminOperation.ReadAdminSubordinateStatement, AdminCommandArgs(scope, subordinate))
        assertTrue(result.isErr); assertEquals("not_published", result.error.token)
    }
    @Test fun artifactReadReturnsExactCommittedBytes() = runTest {
        create("active")
        val row = store.rows.values.single()
        val result = workflow.execute(AdminOperation.ReadAdminSubordinateStatement, AdminCommandArgs(scope, subordinate)).value
        assertEquals(row.artifact, result.artifact); assertEquals("application/entity-statement+jwt", result.mediaType)
    }
    @Test fun patchPreservesExtensionsAndIgnoresServerAssignedFields() = runTest {
        create()
        val result = workflow.execute(AdminOperation.PatchAdminSubordinate, AdminCommandArgs(scope, subordinate,
            document = doc("""{"extension":{"new":1},"etag":"attacker","updated_at":0}"""))).value
        assertEquals(doc("""{"kept":true,"new":1}"""), result.document?.get("extension"))
        assertNotEquals("attacker", result.etag)
        assertNotEquals(JsonPrimitive(0), result.document?.get("updated_at"))
    }
    @Test fun revokedCannotReactivateThroughStatusOrPatchButPutRecreates() = runTest {
        create()
        workflow.execute(AdminOperation.DeleteAdminSubordinate, AdminCommandArgs(scope, subordinate))
        val rejected = workflow.execute(AdminOperation.SetAdminSubordinateStatus, AdminCommandArgs(scope, subordinate, document = doc("""{"status":"active"}""")))
        assertEquals(404, rejected.error.status)
        val recreated = workflow.execute(AdminOperation.ReplaceAdminSubordinate, AdminCommandArgs(scope, subordinate, document = doc("""{"status":"pending"}""")))
        assertTrue(recreated.isOk)
    }
    @Test fun verifyFalseCannotActivateAndConflictingVerifyIsRejected() = runTest {
        create()
        val result = workflow.execute(AdminOperation.SetAdminSubordinateStatus, AdminCommandArgs(scope, subordinate, document = doc("""{"status":"active","verify":false}""")))
        assertEquals(400, result.error.status)
        val conflict = workflow.execute(AdminOperation.PatchAdminSubordinate, AdminCommandArgs(scope, subordinate, document = doc("""{"verify":false}"""), query = mapOf("verify" to "true")))
        assertEquals(400, conflict.error.status)
    }
    @Test fun unsupportedRoleRejectsEverySubordinateOperation() = runTest {
        protocol.roles = listOf("leaf")
        val result = create()
        assertEquals("unsupported_resource", result.error.token); assertEquals(404, result.error.status)
    }
    @Test fun pathIdentityCannotBeOverriddenByBody() = runTest {
        create()
        val result = workflow.execute(AdminOperation.ReplaceAdminSubordinate, AdminCommandArgs(scope, subordinate, document = doc("""{"entity_id":"https://other.example","status":"pending"}""")))
        assertEquals(400, result.error.status); assertEquals(1, store.revision)
    }
    @Test fun listDefaultsToActiveAndHonorsLimitAndContinuation() = runTest {
        create("active")
        workflow.execute(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(scope, document = doc("""{"entity_id":"https://second.example","status":"active"}""")))
        workflow.execute(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(scope, document = doc("""{"entity_id":"https://third.example","status":"pending"}""")))
        val first = workflow.execute(AdminOperation.ListAdminSubordinates, AdminCommandArgs(scope, query = mapOf("limit" to "1"))).value.document!!
        assertEquals(1, first["items"]?.jsonArray?.size); assertNotNull(first["next"])
        assertNull(first["items"]?.jsonArray?.first()?.jsonObject?.get("jwks"))
        val next = workflow.execute(AdminOperation.ListAdminSubordinates, AdminCommandArgs(scope, query = mapOf("limit" to "1", "cursor" to first["next"]!!.jsonPrimitive.content))).value.document!!
        assertEquals(1, next["items"]?.jsonArray?.size); assertNull(next["next"])
    }
    @Test fun typeRevocationAtomicallyRevokesEveryGrant() = runTest {
        val type = "https://ta.example/mark"
        workflow.execute(AdminOperation.CreateAdminTrustMarkType, AdminCommandArgs(scope, document = doc("""{"trust_mark_type":"$type"}""")))
        workflow.execute(AdminOperation.ReplaceAdminTrustMarkGrant, AdminCommandArgs(scope, resourceId = type, subjectId = subordinate, document = doc("""{"status":"active"}""")))
        workflow.execute(AdminOperation.DeleteAdminTrustMarkType, AdminCommandArgs(scope, resourceId = type))
        val grant = store.rows[AdminDocumentKey("trust-mark-grant", type, subordinate)]!!
        assertEquals("revoked", grant.document["status"]?.jsonPrimitive?.content); assertNull(grant.artifact)
    }
    @Test fun documentNullIsRejectedOutsideMergePatch() = runTest {
        val result = workflow.execute(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(scope, document = doc("""{"entity_id":"$subordinate","status":"pending","metadata":null}""")))
        assertEquals("invalid_request", result.error.token)
    }
    @Test fun signingKeyChangeAfterPreparationRejectsPublication() = runTest {
        store.beforeCommit = { store.keyState = "changed" }
        val result = create("active")
        assertEquals(412, result.error.status)
        assertTrue(store.rows.isEmpty())
    }
    @Test fun grantCannotCommitAfterItsIssuanceTypeWasRevoked() = runTest {
        val type = "https://ta.example/mark"
        workflow.execute(AdminOperation.CreateAdminTrustMarkType, AdminCommandArgs(scope, document = doc("""{"trust_mark_type":"$type"}""")))
        val typeKey = AdminDocumentKey("trust-mark-type", type)
        store.beforeCommit = {
            val row = store.rows.getValue(typeKey)
            store.rows[typeKey] = row.copy(etag = "revoked", document = JsonObject(row.document + ("status" to JsonPrimitive("revoked"))))
        }
        val result = workflow.execute(AdminOperation.ReplaceAdminTrustMarkGrant, AdminCommandArgs(scope, resourceId = type, subjectId = subordinate, document = doc("""{"status":"active"}""")))
        assertEquals(412, result.error.status)
        assertFalse(store.rows.containsKey(AdminDocumentKey("trust-mark-grant", type, subordinate)))
    }
    @Test fun typeDeletionIncludesGrantCreatedAfterWorkflowListedChildren() = runTest {
        val type = "https://ta.example/mark"
        workflow.execute(AdminOperation.CreateAdminTrustMarkType, AdminCommandArgs(scope, document = doc("""{"trust_mark_type":"$type"}""")))
        val grantKey = AdminDocumentKey("trust-mark-grant", type, subordinate)
        store.beforeCommit = {
            store.rows[grantKey] = AdminStoredDocument(grantKey, doc("""{"entity_id":"$subordinate","trust_mark_type":"$type","status":"active"}"""), "concurrent", 1, "jwt")
        }
        val result = workflow.execute(AdminOperation.DeleteAdminTrustMarkType, AdminCommandArgs(scope, resourceId = type))
        assertTrue(result.isOk)
        assertEquals("revoked", store.rows.getValue(grantKey).document.string("status"))
        assertNull(store.rows.getValue(grantKey).artifact)
    }
    @Test fun commercialQuotaCountsPendingAndSuspendedAndDoesNotBlockNonIncreasingEdits() = runTest {
        val limited = scope.copy(subordinateLimit = 1)
        val first = workflow.execute(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(limited, document = doc("""{"entity_id":"$subordinate","status":"pending"}""")))
        assertTrue(first.isOk)
        val rejected = workflow.execute(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(limited, document = doc("""{"entity_id":"https://second.example","status":"suspended"}""")))
        assertEquals(409, rejected.error.status)
        assertEquals(1, store.rows.size)
        val edit = workflow.execute(AdminOperation.PatchAdminSubordinate, AdminCommandArgs(limited, subordinate, document = doc("""{"metadata":{"openid_provider":{"organization_name":"Changed"}}}""")))
        assertTrue(edit.isOk)
    }
    @Test fun problemMetadataRetainsTokenStatusAndPointerForRoutedCommands() {
        val error = AdminError("invalid_federation_claim", 400, "Bad claim", "/metadata")
        assertEquals("invalid_federation_claim", error.meta["oidfedAdminToken"])
        assertEquals(400, error.meta["oidfedAdminStatus"])
        assertEquals("/metadata", error.meta["oidfedAdminPointer"])
        assertFalse(AdminError("not_found", 404, "Missing").meta.containsKey("oidfedAdminPointer"))
    }

    @Test fun existingActiveOrSuspendedCannotReturnToPending() = runTest {
        create("active")
        val pending = workflow.execute(AdminOperation.SetAdminSubordinateStatus, AdminCommandArgs(scope, subordinate, document = doc("""{"status":"pending"}""")))
        assertEquals(409, pending.error.status)
        workflow.execute(AdminOperation.SetAdminSubordinateStatus, AdminCommandArgs(scope, subordinate, document = doc("""{"status":"suspended"}""")))
        val suspendedToPending = workflow.execute(AdminOperation.PatchAdminSubordinate, AdminCommandArgs(scope, subordinate, document = doc("""{"status":"pending"}""")))
        assertEquals(409, suspendedToPending.error.status)
    }
    @Test fun artifactReadRejectsExpiredCommittedJwt() = runTest {
        create("active")
        val key = AdminDocumentKey("subordinate", subordinate)
        val row = store.rows.getValue(key)
        val expired = "header." + kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT).encode("{\"exp\":1}".encodeToByteArray()) + ".signature"
        store.rows[key] = row.copy(artifact = expired)
        val result = workflow.execute(AdminOperation.ReadAdminSubordinateStatement, AdminCommandArgs(scope, subordinate))
        assertEquals("not_published", result.error.token)
    }

    private fun artifactWithClaims(claims: String) = "header." + kotlin.io.encoding.Base64.UrlSafe
        .withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT).encode(claims.encodeToByteArray()) + ".original-signature"

    @Test fun scientificAndFractionalArtifactExpiryPreservesExactCommittedBytes() = runTest {
        val cases = listOf(
            Triple(AdminOperation.ReadAdminConfigurationStatement, AdminDocumentKey("configuration"), AdminCommandArgs(scope)),
            Triple(AdminOperation.ReadAdminSubordinateStatement, AdminDocumentKey("subordinate", subordinate), AdminCommandArgs(scope, subordinate)),
            Triple(AdminOperation.ReadAdminTrustMarkToken, AdminDocumentKey("trust-mark-grant", "type", subordinate), AdminCommandArgs(scope, "type", subordinate)),
        )
        for (expiry in listOf("9.999999999E9", "9999999999.75")) for ((operation, key, args) in cases) {
            val original = artifactWithClaims("""{"exp":$expiry,"extension":"preserved"}""")
            store.rows[key] = AdminStoredDocument(key, doc("""{"status":"active"}"""), "etag", 1, original)
            val result = workflow.execute(operation, args)
            assertTrue(result.isOk, "$operation/$expiry: $result")
            assertEquals(original, result.value.artifact)
        }
    }

    @Test fun artifactExpiryRejectsMissingInvalidNonfiniteAndStringNumericDates() = runTest {
        val key = AdminDocumentKey("subordinate", subordinate)
        for (claims in listOf("{}", """{"exp":null}""", """{"exp":true}""", """{"exp":{}}""",
            """{"exp":"9999999999"}""", """{"exp":"9.999999999E9"}""", """{"exp":1e999}""",
            """{"exp":NaN}""", """{"exp":Infinity}""", """{"exp":1E0}""", """{"exp":1.75}""")) {
            store.rows[key] = AdminStoredDocument(key, doc("""{"status":"active"}"""), "etag", 1, artifactWithClaims(claims))
            val result = workflow.execute(AdminOperation.ReadAdminSubordinateStatement, AdminCommandArgs(scope, subordinate))
            assertTrue(result.isErr, claims)
            assertEquals(404, result.error.status, claims)
            assertEquals("not_published", result.error.token, claims)
        }
    }

    @Test fun fractionalExpiryIsComparedWithoutTruncationAtTheActualInstant() {
        val artifact = artifactWithClaims("""{"exp":1792497000.75}""")
        assertFalse(adminArtifactExpired(artifact, kotlin.time.Instant.fromEpochSeconds(1792497000, 500_000_000)))
        assertTrue(adminArtifactExpired(artifact, kotlin.time.Instant.fromEpochSeconds(1792497000, 750_000_000)))
        assertTrue(adminArtifactExpired(artifact, kotlin.time.Instant.fromEpochSeconds(1792497000, 900_000_000)))
    }

    @Test fun stagedPublicationRemainsHiddenUntilExplicitFinalizeAndRetriesAreIdempotent() = runTest {
        val context = doc("""{"priorRevision":1,"generation":2}""")
        val staged = workflow.stage(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(scope,
            document = doc("""{"entity_id":"$subordinate","status":"active"}"""), activationContext = context))
        assertTrue(staged.isOk); assertNotNull(staged.value.artifact)
        assertTrue(store.rows.isEmpty())
        val pending = workflow.pendingStages(scope).value.single()
        assertEquals(context, pending.activationContext)
        val hidden = workflow.execute(AdminOperation.ReadAdminSubordinateStatement, AdminCommandArgs(scope, subordinate))
        assertEquals("not_published", hidden.error.token)
        val finalized = workflow.finalizeStage(scope, pending.digest)
        assertTrue(finalized.isOk); assertEquals(staged.value.artifact, finalized.value.artifact)
        val revision = store.revision
        assertTrue(workflow.finalizeStage(scope, pending.digest).isOk)
        assertEquals(revision, store.revision); assertTrue(workflow.pendingStages(scope).value.isEmpty())
    }
    @Test fun stagedPublicationCannotOverwriteLaterStandardWrite() = runTest {
        val staged = workflow.stage(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(scope,
            document = doc("""{"entity_id":"$subordinate","status":"active"}"""))).value
        create("pending")
        val finalized = workflow.finalizeStage(scope, staged.stageDigest!!)
        assertEquals(412, finalized.error.status)
        assertEquals("pending", store.rows.getValue(AdminDocumentKey("subordinate", subordinate)).document.string("status"))
    }


    private fun lifetimePolicy(revision: Long, value: Long = 600, explicit: Boolean = false) =
        AdminLifetimePolicyContext(AdminLifetimePolicyBinding("tenant", "federation", "profile-incarnation"),
            AdminLifetimeKind.ENTITY_CONFIGURATION, revision, value, explicit)

    @Test fun pendingLifetimePolicyAppliesOnceAndLaterNativeSameValueWriteWins() = runTest {
        val key = AdminDocumentKey("configuration")
        val first = workflow.execute(AdminOperation.ReplaceAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"metadata":{}}"""), lifetimePolicy = lifetimePolicy(0)))
        assertTrue(first.isOk)
        assertEquals(JsonPrimitive(600), store.rows.getValue(key).document["lifetime"])
        val native = workflow.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"lifetime":600}"""), lifetimePolicy = lifetimePolicy(1, 900, explicit = true)))
        assertTrue(native.isOk)
        val unrelated = workflow.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"extension":true}"""), lifetimePolicy = lifetimePolicy(1, 900)))
        assertTrue(unrelated.isOk)
        assertEquals(JsonPrimitive(600), store.rows.getValue(key).document["lifetime"])
        assertEquals(1L, store.readLifetimeAcknowledgement(scope, key)!!.revision)
        assertFalse(unrelated.value.document!!.containsKey("lifetimePolicy"))
        assertFalse(adminSignedClaims(store.rows.getValue(key).document).containsKey("lifetimePolicy"))
    }

    @Test fun syntheticReplacementPreservesNativeLifetimeAndLegacyBaseline() = runTest {
        val key = AdminDocumentKey("configuration")
        workflow.execute(AdminOperation.ReplaceAdminConfiguration, AdminCommandArgs(scope, document = doc("""{"lifetime":42,"custom_extension":{"nested":true}}""")))
        assertTrue(workflow.execute(AdminOperation.ReplaceAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"lifetime":900}"""), lifetimePolicy = lifetimePolicy(0, 900))).isOk)
        assertEquals(JsonPrimitive(42), store.rows.getValue(key).document["lifetime"])
        assertEquals(doc("""{"nested":true}"""), store.rows.getValue(key).document["custom_extension"])
        assertTrue(workflow.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"lifetime":null}"""), lifetimePolicy = lifetimePolicy(0, explicit = true))).isOk)
        assertTrue(workflow.execute(AdminOperation.ReplaceAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"lifetime":900}"""), lifetimePolicy = lifetimePolicy(0, 900))).isOk)
        assertFalse(store.rows.getValue(key).document.containsKey("lifetime"))
    }

    @Test fun acknowledgedNativeLifetimeRemovalIsNotReseededByPublication() = runTest {
        val key = AdminDocumentKey("configuration")
        workflow.execute(AdminOperation.ReplaceAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"lifetime":600}"""), lifetimePolicy = lifetimePolicy(0, explicit = true)))
        for (operation in listOf(AdminOperation.PatchAdminConfiguration, AdminOperation.ReplaceAdminConfiguration)) {
            val body = if (operation.method == "PATCH") doc("""{"lifetime":null}""") else doc("""{"metadata":{}}""")
            assertTrue(workflow.execute(operation, AdminCommandArgs(scope, document = body,
                lifetimePolicy = lifetimePolicy(1, 900, explicit = true))).isOk)
            assertTrue(workflow.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
                document = doc("""{"extension":true}"""), lifetimePolicy = lifetimePolicy(1, 900))).isOk)
            assertFalse(store.rows.getValue(key).document.containsKey("lifetime"))
        }
        assertTrue(workflow.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"extension":false}"""), lifetimePolicy = lifetimePolicy(2, 1200))).isOk)
        assertEquals(JsonPrimitive(1200), store.rows.getValue(key).document["lifetime"])
    }

    @Test fun staleLifetimeRevisionAndForeignBindingCannotPublish() = runTest {
        workflow.execute(AdminOperation.ReplaceAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"lifetime":600}"""), lifetimePolicy = lifetimePolicy(2, explicit = true)))
        val before = store.rows.getValue(AdminDocumentKey("configuration"))
        val stale = workflow.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"extension":true}"""), lifetimePolicy = lifetimePolicy(1)))
        assertEquals("precondition_failed", stale.error.token)
        val foreign = workflow.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"extension":true}"""), lifetimePolicy = lifetimePolicy(2).copy(
                binding = AdminLifetimePolicyBinding("other-tenant", "federation", "profile-incarnation"))))
        assertEquals("conflict", foreign.error.token)
        assertEquals(before, store.rows.getValue(AdminDocumentKey("configuration")))
    }

    @Test fun absentContextUsesPortWithNativeIntentWhileSuppliedFalseRemainsRetained() = runTest {
        val key = AdminDocumentKey("configuration")
        protocol.policyContext = lifetimePolicy(0)
        assertTrue(workflow.execute(AdminOperation.ReplaceAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"metadata":{}}"""))).isOk)
        assertFalse(store.rows.getValue(key).document.containsKey("lifetime"))
        protocol.policyContext = lifetimePolicy(1, 900)
        assertTrue(workflow.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"lifetime":null}"""))).isOk)
        assertEquals(1L, store.readLifetimeAcknowledgement(scope, key)!!.revision)
        assertFalse(store.rows.getValue(key).document.containsKey("lifetime"))
        assertTrue(workflow.execute(AdminOperation.ReplaceAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"lifetime":900}"""), lifetimePolicy = lifetimePolicy(1, 900))).isOk)
        assertFalse(store.rows.getValue(key).document.containsKey("lifetime"))
    }

    @Test fun recreationPreservesSamePolicyWatermarkButResetsNativePrecedence() = runTest {
        val key = AdminDocumentKey("subordinate", subordinate)
        val context = lifetimePolicy(2, 600, true).copy(kind = AdminLifetimeKind.SUBORDINATE_STATEMENT)
        assertTrue(workflow.execute(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(scope,
            document = doc("""{"entity_id":"$subordinate","status":"active","lifetime":50}"""), lifetimePolicy = context)).isOk)
        assertTrue(workflow.execute(AdminOperation.DeleteAdminSubordinate, AdminCommandArgs(scope,
            resourceId = subordinate, lifetimePolicy = context.copy(explicitLifetimeWrite = false))).isOk)
        val oldIncarnation = store.readLifetimeAcknowledgement(scope, key)!!.documentIncarnation
        val body = doc("""{"entity_id":"$subordinate","status":"active"}""")
        val stale = workflow.execute(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(scope,
            document = body, lifetimePolicy = context.copy(revision = 1, explicitLifetimeWrite = false)))
        assertEquals(412, stale.error.status)
        assertTrue(workflow.execute(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(scope,
            document = body, lifetimePolicy = context.copy(explicitLifetimeWrite = false))).isOk)
        assertEquals(JsonPrimitive(600), store.rows.getValue(key).document["lifetime"])
        assertNotEquals(oldIncarnation, store.readLifetimeAcknowledgement(scope, key)!!.documentIncarnation)
    }

    @Test fun policyBoundRecreationRequiresSameBindingAndPreservesWatermarkWhilePending() = runTest {
        val key = AdminDocumentKey("subordinate", subordinate)
        val context = lifetimePolicy(2, 600).copy(kind = AdminLifetimeKind.SUBORDINATE_STATEMENT)
        val body = doc("""{"entity_id":"$subordinate","status":"active"}""")
        workflow.execute(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(scope, document = body, lifetimePolicy = context))
        workflow.execute(AdminOperation.DeleteAdminSubordinate, AdminCommandArgs(scope, resourceId = subordinate, lifetimePolicy = context))
        val noContext = workflow.execute(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(scope, document = body))
        assertEquals(412, noContext.error.status)
        val foreign = workflow.execute(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(scope, document = body,
            lifetimePolicy = context.copy(binding = context.binding.copy(incarnationId = "old-profile"))))
        assertEquals(409, foreign.error.status)
        val pending = doc("""{"entity_id":"$subordinate","status":"pending"}""")
        assertTrue(workflow.execute(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(scope, document = pending,
            lifetimePolicy = context)).isOk)
        assertEquals(2L, store.readLifetimeAcknowledgement(scope, key)!!.revision)
        assertEquals(JsonPrimitive(600), store.rows.getValue(key).document["lifetime"])
        val staleActivation = workflow.execute(AdminOperation.SetAdminSubordinateStatus, AdminCommandArgs(scope,
            resourceId = subordinate, document = doc("""{"status":"active"}"""), lifetimePolicy = context.copy(revision = 1)))
        assertEquals(412, staleActivation.error.status)
    }

}
