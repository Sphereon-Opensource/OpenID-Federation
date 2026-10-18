package com.sphereon.openid.fed.admin

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.core.api.conf.Env
import com.sphereon.openid.fed.persistence.Database
import com.sphereon.openid.fed.persistence.database.JavaUuidStringAdapter
import com.sphereon.openid.fed.persistence.models.*
import com.sphereon.openid.fed.services.command.renewal.PublishedStatementCacheInvalidator
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.asCoreApiServiceGraph
import com.sphereon.crypto.core.kms.asKeyManagerServiceGraph
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.crypto.jose.jws.JwtServiceImpl
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderConfig
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderFactoryImpl
import com.sphereon.di.context.PrincipalType
import com.sphereon.openid.fed.core.config.*
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.services.command.jwk.*
import com.sphereon.openid.fed.services.command.subordinate.*
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import io.mockk.*


/** Real PostgreSQL adapter tests. An explicit dedicated test URL is required; public app schema is never touched. */
class SqlAdminDocumentStoreTest {
    private val schema = "oidf_admin_store_" + UUID.randomUUID().toString().replace("-", "")
    private lateinit var url: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var source: HikariDataSource
    private lateinit var database: Database
    private lateinit var store: SqlAdminDocumentStore
    private lateinit var scope: AdminNodeScope
    private val invalidated = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before fun openIsolatedSchema() {
        val configured = Env.get("OIDF_ADMIN_TEST_DATASOURCE_URL")
        Assume.assumeTrue("Set OIDF_ADMIN_TEST_DATASOURCE_URL for isolated SQL adapter checks", !configured.isNullOrBlank())
        url = configured!!
        user = Env.get("OIDF_DATASOURCE_USER") ?: Env.get("DATASOURCE_USER") ?: "postgres"
        password = Env.get("OIDF_DATASOURCE_PASSWORD") ?: Env.get("DATASOURCE_PASSWORD") ?: "postgres"
        DriverManager.getConnection(url, user, password).use { connection -> connection.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        source = HikariDataSource(HikariConfig().apply {
            jdbcUrl = this@SqlAdminDocumentStoreTest.url; username = this@SqlAdminDocumentStoreTest.user; this.password = this@SqlAdminDocumentStoreTest.password
            maximumPoolSize = 4; connectionInitSql = "SET search_path TO ${this@SqlAdminDocumentStoreTest.schema}, public"
        })
        val driver = source.asJdbcDriver()
        Database.Schema.create(driver)
        database = Database(
            driver,
            AccountAdapter = Account.Adapter(JavaUuidStringAdapter),
            AccountMetadataPolicyRevisionAdapter = AccountMetadataPolicyRevision.Adapter(JavaUuidStringAdapter),
            AccountSigningKeyAdapter = com.sphereon.openid.fed.persistence.models.AccountSigningKey.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            AuthorityHintAdapter = AuthorityHint.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            CritAdapter = Crit.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            EntityConfigurationStatementAdapter = EntityConfigurationStatement.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            JwkAdapter = Jwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            MetadataAdapter = com.sphereon.openid.fed.persistence.models.Metadata.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            ReceivedTrustMarkAdapter = ReceivedTrustMark.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            SubordinateAdapter = Subordinate.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            SubordinateJwkAdapter = SubordinateJwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            SubordinateMetadataAdapter = SubordinateMetadata.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            SubordinateStatementAdapter = SubordinateStatement.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            TrustMarkAdapter = TrustMark.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            TrustMarkIssuerAdapter = TrustMarkIssuer.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            TrustMarkOwnerAdapter = TrustMarkOwner.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            TrustMarkTypeAdapter = TrustMarkType.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            MetadataPolicyAdapter = MetadataPolicy.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            TrustAnchorHintAdapter = TrustAnchorHint.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            SubordinateConstraintAdapter = SubordinateConstraint.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            ForeignSubordinateStatementAdapter = ForeignSubordinateStatement.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
        )
        val account = database.accountQueries.create("store-test", "https://ta.example").executeAsOne()
        scope = AdminNodeScope(account.id, "https://ta.example")
        store = SqlAdminDocumentStore(object : PublishedStatementCacheInvalidator {
            override suspend fun published(statementJwt: String) { invalidated += statementJwt }
        }, database)
    }
    @After fun closeIsolatedSchema() {
        if (::source.isInitialized) source.close()
        if (::url.isInitialized) DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }
    private fun doc(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun jwt(subject: String, tag: String): String = "eyJhbGciOiJSUzI1NiIsImtpZCI6ImZpeHR1cmUiLCJ0eXAiOiJlbnRpdHktc3RhdGVtZW50K2p3dCJ9." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
        """{"iss":"https://ta.example","sub":"$subject","iat":1,"exp":9999999999,"tag":"$tag"}""".toByteArray()) + ".signature"
    private suspend fun configuration(): AdminStoredDocument = store.read(scope, AdminDocumentKey("configuration"))!!

    @Test fun existingPublishedBytesAreImportedWithoutSigningAndCommittedReadDoesNotTakeOver(): Unit = runBlocking {
        val artifact = jwt("https://ta.example", "legacy")
        database.entityConfigurationStatementQueries.create(scope.accountId, artifact, 9999999999).executeAsOne()
        val sub = database.subordinateQueries.create(scope.accountId, "https://op.example").executeAsOne()
        val subordinate = jwt("https://op.example", "legacy-sub")
        database.subordinateStatementQueries.create(sub.id, "https://ta.example", "https://op.example", subordinate, 9999999999).executeAsOne()
        assertNull(store.readCommitted(scope, AdminDocumentKey("configuration")))
        assertEquals(artifact, configuration().artifact)
        assertEquals(subordinate, store.readCommitted(scope, AdminDocumentKey("subordinate", "https://op.example"))?.artifact)
        assertTrue(invalidated.isEmpty())
    }
    @Test fun trustedExternalAdoptionPreservesExactBytesAndNeverOverwritesExistingCanonicalState(): Unit = runBlocking {
        val artifact = jwt("https://ta.example", "edk")
        val config = AdminStoredDocument(AdminDocumentKey("configuration"), doc("""{"entity_id":"https://ta.example"}"""), "ignored", 1, artifact)
        assertTrue(store.adopt(scope, listOf(config)))
        assertEquals(artifact, store.readCommitted(scope, config.key)?.artifact)
        assertFalse(store.adopt(scope, listOf(config.copy(artifact = "other"))))
        assertEquals(artifact, store.readCommitted(scope, config.key)?.artifact)
    }
    @Test fun concurrentCompareAndSwapCommitsOnlyOneWriterAndRecreationRejectsOldEtag(): Unit = runBlocking {
        val original = configuration()
        val results = listOf("one", "two").map { value -> async(Dispatchers.IO) {
            store.commit(scope, listOf(AdminMutation(original.key, original.etag, JsonObject(original.document + ("extension" to JsonPrimitive(value))), jwt("https://ta.example", value))))
        }}.awaitAll()
        assertEquals(1, results.count { it != null })
        val key = AdminDocumentKey("subordinate", "https://op.example")
        val first = store.commit(scope, listOf(AdminMutation(key, null, doc("""{"entity_id":"https://op.example","status":"pending"}"""))))!!.first()
        val revoked = store.commit(scope, listOf(AdminMutation(key, first.etag, JsonObject(first.document + ("status" to JsonPrimitive("revoked"))))))!!.first()
        val recreated = store.commit(scope, listOf(AdminMutation(key, revoked.etag, first.document, recreate = true)))!!.first()
        assertNotEquals(first.etag, recreated.etag)
        assertNull(store.commit(scope, listOf(AdminMutation(key, first.etag, revoked.document))))
    }
    @Test fun stagedCandidateIsHiddenAndLaterStandardWritePreventsFinalize(): Unit = runBlocking {
        val original = configuration()
        val proposed = JsonObject(original.document + ("extension" to JsonPrimitive("staged")))
        val stage = store.stage(scope, AdminMutation(original.key, original.etag, proposed, jwt("https://ta.example", "staged")))
        assertEquals(original.etag, store.readCommitted(scope, original.key)?.etag)
        assertEquals(stage.digest, store.pendingStages(scope).single().digest)
        val newer = store.commit(scope, listOf(AdminMutation(original.key, original.etag, original.document, jwt("https://ta.example", "newer"))))!!.first()
        assertNull(store.finalizeStage(scope, stage.digest))
        assertEquals(newer.etag, store.readCommitted(scope, original.key)?.etag)
        assertTrue(store.pendingStages(scope).isEmpty())
    }
    @Test fun finalizeIsIdempotentAndWithdrawInvalidatesPreviousArtifactCache(): Unit = runBlocking {
        val original = configuration()
        val artifact = jwt("https://ta.example", "accepted")
        val stage = store.stage(scope, AdminMutation(original.key, original.etag, original.document, artifact))
        val committed = store.finalizeStage(scope, stage.digest)!!
        assertEquals(artifact, committed.artifact)
        assertEquals(committed.etag, store.finalizeStage(scope, stage.digest)?.etag)
        assertTrue(store.pendingStages(scope).isEmpty())
        val key = AdminDocumentKey("subordinate", "https://op.example")
        val subJwt = jwt("https://op.example", "withdraw")
        val sub = store.commit(scope, listOf(AdminMutation(key, null, doc("""{"entity_id":"https://op.example","status":"active"}"""), subJwt)))!!.first()
        store.commit(scope, listOf(AdminMutation(key, sub.etag, JsonObject(sub.document + ("status" to JsonPrimitive("revoked"))))))
        assertTrue(invalidated.count { it == subJwt } >= 2)
    }
    @Test fun keyInsertAndSelectionRollbackWhenPublicationTransactionRejects(): Unit = runBlocking {
        val original = configuration()
        val oldKey = UUID.randomUUID().toString()
        database.jwkQueries.createPrepared(oldKey, scope.accountId, "ES256", "old", "software", "old-ref", "{\"kty\":\"EC\"}")
        database.accountSigningKeyQueries.upsert(scope.accountId, oldKey, 1).executeAsOne()
        val newKey = AdminPreparedKey(UUID.randomUUID().toString(), doc("""{"kty":"EC","kid":"new"}"""), "new", "ES256", "software", "new-ref")
        val badIdentity = JsonObject(original.document + ("entity_id" to JsonPrimitive("https://other.example")))
        val failure = runCatching { store.commit(scope, listOf(AdminMutation(original.key, original.etag, badIdentity, jwt("https://other.example", "bad"),
            keyChange = AdminKeyChange(newKey, newKey.id, true)))) }.exceptionOrNull()
        assertIs<AdminFailure>(failure)
        assertNull(database.jwkQueries.findById(newKey.id).executeAsOneOrNull())
        assertEquals(oldKey, database.accountSigningKeyQueries.findByAccountId(scope.accountId).executeAsOne().jwk_id)
        assertEquals(original.etag, store.readCommitted(scope, original.key)?.etag)
    }
    @Test fun signerFingerprintRejectsKeyChangeDuringPreparation(): Unit = runBlocking {
        val original = configuration()
        val fence = adminKeyState(scope.accountId, database.jwkQueries, database.accountSigningKeyQueries, database.adminDocumentQueries)
        database.jwkQueries.createPrepared(UUID.randomUUID().toString(), scope.accountId, "ES256", "concurrent", "software", "concurrent", "{\"kty\":\"EC\"}")
        assertNull(store.commit(scope, listOf(AdminMutation(original.key, original.etag, original.document, jwt("https://ta.example", "stale"), expectedKeyState = fence))))
        assertEquals(original.etag, store.readCommitted(scope, original.key)?.etag)
    }
    @Test fun absentAndAlreadyRevokedTombstonesFenceEarlierStagedRecreations(): Unit = runBlocking {
        configuration()
        val key = AdminDocumentKey("subordinate", "https://op.example")
        val active = doc("""{"entity_id":"https://op.example","status":"active"}""")
        val stage = store.stage(scope, AdminMutation(key, null, active, jwt("https://op.example", "first")))
        val tombstone = store.commit(scope, listOf(AdminMutation(key, null, JsonObject(active + ("status" to JsonPrimitive("revoked"))))))!!.first()
        assertNull(store.finalizeStage(scope, stage.digest))
        val recreate = store.stage(scope, AdminMutation(key, tombstone.etag, active, jwt("https://op.example", "recreate")))
        val fresh = store.commit(scope, listOf(AdminMutation(key, tombstone.etag, tombstone.document)))!!.first()
        assertNotEquals(tombstone.etag, fresh.etag)
        assertNull(store.finalizeStage(scope, recreate.digest))
    }
    @Test fun capturedIntentGuardBlocksEarlierAndLaterStagesButAllowsNewVersion(): Unit = runBlocking {
        configuration()
        val key = AdminDocumentKey("subordinate", "https://guard.example")
        val active = doc("""{"entity_id":"https://guard.example","status":"active"}""")
        val context = doc("""{"edgeId":"edge-one","intentVersion":7}""")
        val pending = store.stage(scope, AdminMutation(key, null, active, jwt(key.resourceId, "before"), activationContext = context))
        val guard = adminWithdrawalKey(key.resourceId, "edge-one", 7)
        val revoked = JsonObject(active + ("status" to JsonPrimitive("revoked")))
        val tombstone = store.commit(scope, listOf(AdminMutation(key, null, revoked), AdminMutation(guard, null, doc("""{"withdrawn":true}"""))))!!.first()
        assertNull(store.finalizeStage(scope, pending.digest, pending.stageEtag))
        val denied = runCatching { store.stage(scope, AdminMutation(key, tombstone.etag, active, jwt(key.resourceId, "after"), activationContext = context)) }.exceptionOrNull()
        assertEquals(412, assertIs<AdminFailure>(denied).problem.status)
        val next = store.stage(scope, AdminMutation(key, tombstone.etag, active, jwt(key.resourceId, "new-version"), activationContext = doc("""{"edgeId":"edge-one","intentVersion":8}""")))
        val found = store.stagedPublication(scope, next.digest)!!
        assertFalse(found.committed)
        val wrongEtag = runCatching { store.finalizeStage(scope, next.digest, "wrong") }.exceptionOrNull()
        assertEquals(412, assertIs<AdminFailure>(wrongEtag).problem.status)
        assertNotNull(store.finalizeStage(scope, next.digest, found.stageEtag))
        assertTrue(store.stagedPublication(scope, next.digest)!!.committed)
        assertNull(store.stagedPublication(scope, "missing"))
    }
    @Test fun productionKeyPortSignsAndRollsOverAcrossNewPortInstance(): Unit = runBlocking {
        configuration()
        val app = createAdminProtocolTestGraph(this)
        val session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "admin-protocol-" + UUID.randomUUID(), principalType = PrincipalType.ANONYMOUS)
        val execution = session.asCoreApiServiceGraph().serviceExecution
        val providerId = "admin-software-" + UUID.randomUUID()
        val provider = (app as SoftwareKmsProviderFactoryImpl.Graph).softwareKmsProvider.create(SoftwareKmsProviderConfig(id = providerId), execution)
        val keyManager = session.graph.asKeyManagerServiceGraph().keyManagerService
        keyManager.registerProvider(provider, makeDefaultKms = true)
        val realJwt = (session.graph as JwtServiceImpl.Graph).jwtService
        val binder = mockk<OidfConfigBinder>()
        every { binder.getFederationConfig() } returns FederationConfig(rootIdentifier = scope.entityId!!, statementLifetimeSeconds = null, statementRenewal = StatementRenewalConfig(enabled = false))
        every { binder.getEffectiveKmsConfig(any(), any()) } returns KmsConfig(providerId)
        val resolver = mockk<TenantContextResolver>()
        coEvery { resolver.resolveIdentifier(scope.accountId) } returns scope.entityId
        val getKeys = mockk<GetKeysCommand>()
        coEvery { getKeys.execute(any()) } returns IdkResult.ok(emptyArray<com.sphereon.openid.fed.openapi.models.TenantJwk>())
        val creation = CreateKeyCommandImpl(execution, keyManager, getKeys, binder, mockk(relaxed = true))
        var time = System.currentTimeMillis() / 1000
        fun port(signing: JwtService = realJwt) = StandaloneAdminProtocolPort(store, resolver, binder, creation, signing,
            mockk(), mockk(), mockk(), mockk(), database, { time })
        var protocol = port()
        val first = protocol.keys(AdminOperation.CreateAdminKey, AdminCommandArgs(scope, document = doc("""{"generate":{"alg":"ES256"}}""")))
        val firstKid = first.document!!.getValue("kid").jsonPrimitive.content
        val config = doc("""{"entity_id":"https://ta.example","lifetime":3600,"metadata":{"federation_entity":{"federation_fetch_endpoint":"https://ta.example/fetch","federation_list_endpoint":"https://ta.example/list"}},"extension_claim":{"preserved":true}}""")
        var workflow = DefaultAdminWorkflow(store, protocol)
        val published = workflow.execute(AdminOperation.ReplaceAdminConfiguration, AdminCommandArgs(scope, document = config))
        assertTrue(published.isOk)
        val committed = store.readCommitted(scope, AdminDocumentKey("configuration"))!!
        val artifact = workflow.execute(AdminOperation.ReadAdminConfigurationStatement, AdminCommandArgs(scope))
        assertEquals(committed.artifact, artifact.value.artifact)
        assertEquals(firstKid, decodeJWTComponents(committed.artifact!!).header.kid)
        assertEquals(config["extension_claim"], decodeJWTComponents(committed.artifact!!).payload["extension_claim"])
        val invalidPolicy = JsonObject(config + ("metadata_policy" to doc("""{"openid_provider":{"foo":{"essential":"yes"}}}""")))
        assertTrue(workflow.execute(AdminOperation.ReplaceAdminConfiguration, AdminCommandArgs(scope, document = invalidPolicy)).isErr)
        assertEquals(committed.etag, store.readCommitted(scope, committed.key)?.etag)
        val rotated = protocol.keys(AdminOperation.RotateAdminKey, AdminCommandArgs(scope, firstKid, document = doc("""{"generate":{"alg":"ES256"},"switch_after":60}""")))
        val nextKid = rotated.document!!.getValue("kid").jsonPrimitive.content
        assertEquals(firstKid, protocol.node(scope).getValue("signing_kid").jsonPrimitive.content)
        time += 61
        protocol = port() // all scheduling information comes from the persisted key-state row
        workflow = DefaultAdminWorkflow(store, protocol)
        val keyMaintenance = AdminRenewDueStatementsCommand(execution, mockk(), store, protocol, database)
        val listed = keyMaintenance.execute(com.sphereon.openid.fed.services.command.renewal.RenewDueStatementsArgs(1, listOnly = true, keysOnly = true))
        assertEquals(listOf(scope.accountId), listed.value.outcomes.map { it.accountId })
        val maintained = keyMaintenance.execute(com.sphereon.openid.fed.services.command.renewal.RenewDueStatementsArgs(1, accountId = scope.accountId, keysOnly = true))
        assertTrue(maintained.isOk)
        // No administrative read triggered this activation; the independent registered maintenance command did.
        val selectedId = database.accountSigningKeyQueries.findByAccountId(scope.accountId).executeAsOne().jwk_id
        assertEquals(nextKid, database.jwkQueries.findById(selectedId!!).executeAsOne().kid)
        assertTrue(keyMaintenance.execute(com.sphereon.openid.fed.services.command.renewal.RenewDueStatementsArgs(1, listOnly = true, keysOnly = true)).value.outcomes.isEmpty())
        val afterSwitch = store.readCommitted(scope, committed.key)!!
        assertEquals(nextKid, decodeJWTComponents(afterSwitch.artifact!!).header.kid)
        assertEquals(afterSwitch.artifact, workflow.execute(AdminOperation.ReadAdminConfigurationStatement, AdminCommandArgs(scope)).value.artifact)
        protocol.keys(AdminOperation.DeleteAdminKey, AdminCommandArgs(scope, firstKid))
        val publishedKeys = decodeJWTComponents(store.readCommitted(scope, committed.key)!!.artifact!!).payload.getValue("jwks").jsonObject.getValue("keys").jsonArray
        assertEquals(listOf(nextKid), publishedKeys.map { it.jsonObject.getValue("kid").jsonPrimitive.content })
        val last = runCatching { protocol.keys(AdminOperation.DeleteAdminKey, AdminCommandArgs(scope, nextKid)) }.exceptionOrNull()
        assertEquals("last_signing_key", assertIs<AdminFailure>(last).problem.token)
        val retiredPublic = first.document!!.getValue("public_jwk").jsonObject
        val importBody = buildJsonObject { put("import", JsonObject(retiredPublic + ("kid" to JsonPrimitive("external-public-only")))) }
        val privateImport = buildJsonObject { put("import", JsonObject(retiredPublic + mapOf("kid" to JsonPrimitive("forbidden-private"), "d" to JsonPrimitive("private")))) }
        assertIs<AdminFailure>(runCatching { protocol.keys(AdminOperation.CreateAdminKey, AdminCommandArgs(scope, document = privateImport)) }.exceptionOrNull())
        val imported = protocol.keys(AdminOperation.CreateAdminKey, AdminCommandArgs(scope, document = importBody))
        assertEquals(JsonPrimitive(false), imported.document!!["signing"])
        val lastWithPublicOnly = runCatching { protocol.keys(AdminOperation.DeleteAdminKey, AdminCommandArgs(scope, nextKid)) }.exceptionOrNull()
        assertEquals("last_signing_key", assertIs<AdminFailure>(lastWithPublicOnly).problem.token)
        val beforeFailure = store.readCommitted(scope, committed.key)!!
        val beforeKeyCount = database.jwkQueries.findAllByAccountId(scope.accountId).executeAsList().size
        val brokenJwt = mockk<JwtService>()
        coEvery { brokenJwt.createJwsCompact(any()) } throws IllegalStateException("injected signing failure")
        val failed = DefaultAdminWorkflow(store, port(brokenJwt)).execute(AdminOperation.CreateAdminKey, AdminCommandArgs(scope, document = doc("""{"generate":{"alg":"ES256"}}""")))
        assertTrue(failed.isErr)
        assertEquals(beforeFailure.etag, store.readCommitted(scope, committed.key)?.etag)
        assertEquals(beforeKeyCount, database.jwkQueries.findAllByAccountId(scope.accountId).executeAsList().size)
    }
    @Test fun deletingTheOnlyPublicRegistrationDoesNotRequireASigningReplacement(): Unit = runBlocking {
        val config = configuration()
        val id = UUID.randomUUID().toString()
        database.jwkQueries.createPrepared(id, scope.accountId, "ES256", "public-only", "", "", "{}")
        assertNotNull(store.commit(scope, listOf(AdminMutation(config.key, config.etag, config.document, keyChange = AdminKeyChange(revokeKeyId = id)))))
        assertNotNull(database.jwkQueries.findById(id).executeAsOne().revoked_at)
    }
    @Test fun failingScheduledKeyAccountDoesNotStarveTheNextBoundedBatch(): Unit = runBlocking {
        configuration()
        val second = database.accountQueries.create("next-account", "https://next.example").executeAsOne()
        val secondScope = AdminNodeScope(second.id, "https://next.example")
        store.read(secondScope, AdminDocumentKey("configuration"))
        val pending = """{"status":"retiring","new_kid":"replacement","switch_at":1,"switched":false}"""
        database.adminDocumentQueries.upsert(scope.accountId, "key-state", "first", "", pending, "first-state", 1, null)
        database.adminDocumentQueries.upsert(second.id, "key-state", "second", "", pending, "second-state", 2, null)
        val protocol = mockk<AdminProtocolPort>()
        coEvery { protocol.node(any()) } throws IllegalStateException("KMS unavailable for oldest account")
        val command = AdminRenewDueStatementsCommand(testSessionExecution(), mockk(), store, protocol, database, batchSize = 1)
        val listing = com.sphereon.openid.fed.services.command.renewal.RenewDueStatementsArgs(1, listOnly = true, keysOnly = true)
        assertEquals(listOf(scope.accountId), command.execute(listing).value.outcomes.map { it.accountId })
        runCatching { command.execute(listing.copy(listOnly = false, accountId = scope.accountId)) }
        coVerify(exactly = 1) { protocol.node(any()) }
        assertEquals(listOf(second.id), command.execute(listing).value.outcomes.map { it.accountId })
        val firstState = database.adminDocumentQueries.find(scope.accountId, "key-state", "first", "").executeAsOne()
        assertEquals("first-state", firstState.etag)
        assertEquals(pending, firstState.document)
        assertNull(firstState.artifact)
    }
    @Test fun publicListCommandUsesCanonicalLifecycleFiltersAndAccountScope(): Unit = runBlocking {
        configuration()
        val alpha = "https://alpha.example/path%2Fpart"
        val beta = "https://beta.example"
        val revoked = "https://revoked.example"
        val expired = "https://expired.example"
        val type = "https://types.example/type"
        fun sub(id: String, status: String = "active", metadataType: String = "openid_provider", intermediate: Boolean = false) = buildJsonObject {
            put("entity_id", id); put("status", status)
            put("metadata", buildJsonObject {
                put(metadataType, buildJsonObject {})
                if (intermediate) put("federation_entity", buildJsonObject {
                    put("federation_fetch_endpoint", "$id/fetch"); put("federation_list_endpoint", "$id/list")
                })
            })
        }
        val expiredJwt = "eyJhbGciOiJSUzI1NiIsImtpZCI6ImZpeHR1cmUiLCJ0eXAiOiJlbnRpdHktc3RhdGVtZW50K2p3dCJ9." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":1}""".toByteArray()) + ".s"
        store.commit(scope, listOf(
            AdminMutation(AdminDocumentKey("subordinate", alpha), null, sub(alpha), jwt(alpha, "published")),
            AdminMutation(AdminDocumentKey("subordinate", beta), null, sub(beta, metadataType = "openid_relying_party", intermediate = true), jwt(beta, "published")),
            AdminMutation(AdminDocumentKey("subordinate", revoked), null, sub(revoked, "revoked"), jwt(revoked, "old")),
            AdminMutation(AdminDocumentKey("subordinate", expired), null, sub(expired), expiredJwt),
            AdminMutation(AdminDocumentKey("trust-mark-grant", type, alpha), null, doc("""{"status":"active"}"""), jwt(alpha, "mark"))
        ))
        val foreign = database.accountQueries.create("foreign-account", beta).executeAsOne()
        val foreignScope = AdminNodeScope(foreign.id, beta)
        store.read(foreignScope, AdminDocumentKey("configuration"))
        val nested = AdminDocumentKey("subordinate", "https://descendant.example")
        val nestedRow = store.commit(foreignScope, listOf(AdminMutation(nested, null, sub(nested.resourceId), jwt(nested.resourceId, "nested"))))!!.single()
        val legacyChild = database.subordinateQueries.create(foreign.id, nested.resourceId).executeAsOne()
        database.subordinateStatementQueries.create(legacyChild.id, beta, nested.resourceId, jwt(nested.resourceId, "historical"), 9999999999).executeAsOne()
        val legacyDetails = mockk<FindSubordinatesByAccountCommand>()
        val legacyIds = mockk<FindSubordinatesByAccountAsArrayCommand>()
        val command = ListPublishedSubordinatesCommandImpl(testSessionExecution(), legacyDetails, legacyIds, database)
        suspend fun list(args: ListPublishedSubordinatesArgs) = command.execute(args).also { assertTrue(it.isOk) }.value.toSet()
        val base = ListPublishedSubordinatesArgs(scope.accountId)
        assertEquals(setOf(alpha, beta), list(base))
        assertEquals(setOf(alpha), list(base.copy(entityType = "openid_provider")))
        assertEquals(setOf(beta), list(base.copy(trustMarked = false)))
        assertEquals(setOf(alpha), list(base.copy(trustMarked = true, trustMarkType = type)))
        assertTrue(list(base.copy(trustMarkType = "https://types.example/unknown")).isEmpty())
        assertEquals(setOf(beta), list(base.copy(intermediate = true)))
        assertEquals(setOf(nested.resourceId), list(ListPublishedSubordinatesArgs(foreign.id)))
        store.commit(foreignScope, listOf(AdminMutation(nested, nestedRow.etag, sub(nested.resourceId, "revoked"))))
        assertEquals(setOf(beta), list(base.copy(intermediate = true)))
        coVerify(exactly = 0) { legacyDetails.execute(any()) }
        coVerify(exactly = 0) { legacyIds.execute(any()) }
    }
    @Test fun publicListIntermediateFilterUsesPublishedFederationMetadataWithoutLocalChildren(): Unit = runBlocking {
        configuration()
        val remote = "https://remote-intermediate.example"
        val emptyLocal = "https://empty-local.example"
        val leaf = "https://leaf.example"
        val partial = "https://partial.example"
        fun subordinate(id: String, fetch: Boolean, listing: Boolean) = buildJsonObject {
            put("entity_id", id); put("status", "active")
            put("metadata", buildJsonObject { put("federation_entity", buildJsonObject {
                if (fetch) put("federation_fetch_endpoint", "$id/fetch")
                if (listing) put("federation_list_endpoint", "$id/list")
            }) })
        }
        store.commit(scope, listOf(remote, emptyLocal, leaf, partial).map { id ->
            AdminMutation(AdminDocumentKey("subordinate", id), null,
                subordinate(id, id != leaf, id == remote || id == emptyLocal), jwt(id, "current"))
        })
        val empty = database.accountQueries.create("empty-local", emptyLocal).executeAsOne()
        store.read(AdminNodeScope(empty.id, emptyLocal), AdminDocumentKey("configuration"))
        val localLeaf = database.accountQueries.create("leaf-with-local-child", leaf).executeAsOne()
        val child = database.subordinateQueries.create(localLeaf.id, "https://local-child.example").executeAsOne()
        database.subordinateStatementQueries.create(child.id, leaf, child.identifier, jwt(child.identifier, "legacy-child"), 9999999999).executeAsOne()
        val command = ListPublishedSubordinatesCommandImpl(testSessionExecution(), mockk(), mockk(), database)
        assertEquals(setOf(remote, emptyLocal), command.execute(ListPublishedSubordinatesArgs(scope.accountId, intermediate = true)).value.toSet())
        assertEquals(setOf(leaf, partial), command.execute(ListPublishedSubordinatesArgs(scope.accountId, intermediate = false)).value.toSet())
        assertNull(database.accountQueries.findByIdentifier(remote).executeAsOneOrNull())
        val unmanaged = database.accountQueries.create("unmanaged-superior", "https://unmanaged.example").executeAsOne()
        val legacy = database.subordinateQueries.create(unmanaged.id, remote).executeAsOne()
        database.subordinateMetadataQueries.create(unmanaged.id, legacy.id, "federation_entity", subordinate(remote, true, true)["metadata"]!!.jsonObject["federation_entity"].toString()).executeAsOne()
        val details = mockk<FindSubordinatesByAccountCommand>()
        coEvery { details.execute(FindSubordinatesByAccountArgs(unmanaged.id)) } returns IdkResult.ok(arrayOf(
            com.sphereon.openid.fed.openapi.models.Subordinate(legacy.id, unmanaged.id, remote, "2026-01-01T00:00:00Z")))
        val unmanagedCommand = ListPublishedSubordinatesCommandImpl(testSessionExecution(), details, mockk(), database)
        assertEquals(setOf(remote), unmanagedCommand.execute(ListPublishedSubordinatesArgs(unmanaged.id, intermediate = true)).value.toSet())
    }
    @Test fun publicListCommandRetainsUnmanagedFastPathBehindRegisteredCommands(): Unit = runBlocking {
        val details = mockk<FindSubordinatesByAccountCommand>()
        val ids = mockk<FindSubordinatesByAccountAsArrayCommand>()
        coEvery { ids.execute(any()) } returns IdkResult.ok(arrayOf("https://legacy.example"))
        val command = ListPublishedSubordinatesCommandImpl(testSessionExecution(), details, ids, database)
        val result = command.execute(ListPublishedSubordinatesArgs(scope.accountId))
        assertEquals(listOf("https://legacy.example"), result.value.toList())
        coVerify(exactly = 1) { ids.execute(FindSubordinatesByAccountAsArrayArgs(scope.accountId)) }
        assertNull(store.readCommitted(scope, AdminDocumentKey("configuration")))
    }
    @Test fun projectedQuotaIsAtomicAcrossConcurrentCreates(): Unit = runBlocking {
        configuration()
        val limited = scope.copy(subordinateLimit = 1)
        val results = listOf("https://one.example", "https://two.example").map { id -> async(Dispatchers.IO) {
            runCatching { store.commit(limited, listOf(AdminMutation(AdminDocumentKey("subordinate", id), null, doc("""{"entity_id":"$id","status":"pending"}""")))) }
        }}.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, store.listCommitted(scope, "subordinate").size)
        assertEquals(409, (results.single { it.isFailure }.exceptionOrNull() as AdminFailure).problem.status)
    }
}
