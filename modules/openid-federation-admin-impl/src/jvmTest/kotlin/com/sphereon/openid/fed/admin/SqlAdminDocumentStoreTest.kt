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
            TrustMarkTypeRevisionAdapter = TrustMarkTypeRevision.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
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

    @Test fun trustMarkDefaultsAndCoreDefinitionShareOwningVersionAndIncarnation(): Unit = runBlocking {
        val identifier = "https://ta.example/marks/research"
        val core = database.trustMarkTypeQueries
        val original = core.createWithDefinition(identifier, scope.accountId,
            """{"name":"Research","ref":"https://ta.example/old","lifetimeSeconds":600,"extensions":{"core-owned":true}}""").executeAsOne()
        core.insertRevision(original.id, original.version, original.definition).executeAsOne()
        val protocol = mockk<AdminProtocolPort>()
        coEvery { protocol.lifetimePolicy(any(), any()) } returns null
        coEvery { protocol.node(any()) } returns doc("""{"roles":["trust_mark_issuer"]}""")
        coEvery { protocol.validate(any(), any(), any()) } just Runs
        val workflow = DefaultAdminWorkflow(store, protocol)
        val key = AdminDocumentKey("trust-mark-type", identifier)
        val initial = store.read(scope, key)!!
        assertEquals(JsonPrimitive(600), initial.document["lifetime"])
        assertEquals(JsonPrimitive("https://ta.example/old"), initial.document["ref"])
        val replacement = doc("""{"trust_mark_type":"$identifier","lifetime":900,"ref":"https://ta.example/standard","logo_uri":"https://ta.example/logo.png","custom":{"kept":true}}""")
        val written = workflow.execute(AdminOperation.ReplaceAdminTrustMarkType,
            AdminCommandArgs(scope, resourceId = identifier, document = replacement, ifMatch = initial.etag))
        assertTrue(written.isOk)
        val changed = core.findByAccountIdAndIdentifier(scope.accountId, identifier).executeAsOne()
        val definition = doc(changed.definition!!)
        assertEquals(JsonPrimitive(900), definition["lifetimeSeconds"])
        assertEquals(replacement["logo_uri"], definition["logoUri"])
        assertEquals(doc("""{"core-owned":true}"""), definition["extensions"])
        assertEquals(2L, changed.version)
        assertEquals(2, core.findRevisionsByTypeId(changed.id).executeAsList().size)
        val storage = doc(database.adminDocumentQueries.find(scope.accountId, key.collection, identifier, "").executeAsOne().document)
        assertFalse("lifetime" in storage || "ref" in storage || "logo_uri" in storage)
        val etag = store.read(scope, key)!!.etag
        val updated = core.updateDefinition("""{"name":"Research","ref":"https://ta.example/core","lifetimeSeconds":1200,"extensions":{"core-owned":true}}""",
            scope.accountId, changed.id, changed.version).executeAsOne()
        core.insertRevision(updated.id, updated.version, updated.definition).executeAsOne()
        val projected = store.read(scope, key)!!
        assertEquals(JsonPrimitive(1200), projected.document["lifetime"])
        assertEquals(JsonPrimitive("https://ta.example/core"), projected.document["ref"])
        assertEquals(replacement["custom"], projected.document["custom"])
        assertNotEquals(etag, projected.etag)
        assertNull(store.commit(scope, listOf(AdminMutation(key, etag, replacement))))
        core.delete(changed.id).executeAsOne()
        assertEquals(JsonPrimitive("revoked"), store.read(scope, key)!!.document["status"])
        val recreated = core.createWithDefinition(identifier, scope.accountId, """{"name":"Recreated","lifetimeSeconds":1800}""").executeAsOne()
        core.insertRevision(recreated.id, recreated.version, recreated.definition).executeAsOne()
        val incarnation = store.read(scope, key)!!
        assertNotEquals(projected.etag, incarnation.etag)
        assertFalse("custom" in incarnation.document)
        assertNull(store.commit(scope, listOf(AdminMutation(key, projected.etag, replacement))))
    }

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
        val afterFirst = invalidated.count { it == artifact }
        assertEquals(committed.etag, store.finalizeStage(scope, stage.digest)?.etag)
        assertEquals(afterFirst + 1, invalidated.count { it == artifact }, "A repeated finalize drops cached copies again")
        assertTrue(store.pendingStages(scope).isEmpty())
        val key = AdminDocumentKey("subordinate", "https://op.example")
        val subJwt = jwt("https://op.example", "withdraw")
        val sub = store.commit(scope, listOf(AdminMutation(key, null, doc("""{"entity_id":"https://op.example","status":"active"}"""), subJwt)))!!.first()
        store.commit(scope, listOf(AdminMutation(key, sub.etag, JsonObject(sub.document + ("status" to JsonPrimitive("revoked"))))))
        assertTrue(invalidated.count { it == subJwt } >= 2)
    }

    @Test fun scientificExpirySurvivesPendingStageRecoveryAndFinalization(): Unit = runBlocking {
        val original = configuration()
        val artifact = "header." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"exp":9.999999999E9}""".toByteArray()) + ".original-signature"
        val stage = store.stage(scope, AdminMutation(original.key, original.etag, original.document, artifact))
        assertEquals(artifact, store.pendingStages(scope).single().artifact)
        assertEquals(artifact, store.finalizeStage(scope, stage.digest)!!.artifact)
    }

    @Test fun configurationLifetimePatchPublishesScientificExpiryThroughRealSqlWorkflow(): Unit = runBlocking {
        val original = configuration()
        val protocol = mockk<AdminProtocolPort>()
        coEvery { protocol.lifetimePolicy(any(), any()) } returns null
        val artifact = "header." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"exp":9.999999999E9,"lifetime":86400}""".toByteArray()) + ".original-signature"
        coEvery { protocol.node(any()) } returns original.document
        coEvery { protocol.validate(any(), any(), any()) } just Runs
        coEvery { protocol.prepare(any(), any(), any()) } answers {
            assertEquals(JsonPrimitive(86400), thirdArg<JsonObject>()["lifetime"])
            AdminPreparedArtifact(artifact, adminKeyState(scope.accountId, database.jwkQueries,
                database.accountSigningKeyQueries, database.adminDocumentQueries))
        }
        val workflow = DefaultAdminWorkflow(store, protocol)
        val patched = workflow.execute(AdminOperation.PatchAdminConfiguration,
            AdminCommandArgs(scope, document = doc("""{"lifetime":86400}"""), ifMatch = original.etag))
        assertTrue(patched.isOk)
        assertNotEquals(original.etag, patched.value.etag)
        val committed = store.readCommitted(scope, original.key)!!
        assertEquals(JsonPrimitive(86400), committed.document["lifetime"])
        assertEquals(artifact, committed.artifact)
        val read = workflow.execute(AdminOperation.ReadAdminConfigurationStatement, AdminCommandArgs(scope))
        assertTrue(read.isOk)
        assertEquals(artifact, read.value.artifact)
    }

    private fun lifetimeContext(revision: Long, value: Long = 600, explicit: Boolean = false,
        kind: AdminLifetimeKind = AdminLifetimeKind.ENTITY_CONFIGURATION, incarnation: String = "profile-one") =
        AdminLifetimePolicyContext(AdminLifetimePolicyBinding("tenant", "federation", incarnation), kind, revision, value, explicit)

    private fun lifetimeProtocol(context: AdminLifetimePolicyContext? = null): AdminProtocolPort {
        val port = mockk<AdminProtocolPort>()
        val sequence = java.util.concurrent.atomic.AtomicInteger()
        coEvery { port.lifetimePolicy(any(), any()) } returns context
        coEvery { port.node(any()) } returns doc("""{"roles":["trust_anchor"]}""")
        coEvery { port.validate(any(), any(), any()) } just Runs
        coEvery { port.verifySubject(any(), any(), any()) } just Runs
        coEvery { port.prepare(any(), any(), any()) } answers {
            AdminPreparedArtifact(jwt("https://ta.example", "lifetime-${sequence.incrementAndGet()}"),
                adminKeyState(scope.accountId, database.jwkQueries, database.accountSigningKeyQueries, database.adminDocumentQueries))
        }
        return port
    }

    @Test fun lifetimeAcknowledgementAndArtifactRollbackTogetherOnLateSqlFailure(): Unit = runBlocking {
        val original = configuration()
        val workflow = DefaultAdminWorkflow(store, lifetimeProtocol())
        assertTrue(workflow.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"lifetime":50}"""), lifetimePolicy = lifetimeContext(0, explicit = true))).isOk)
        val before = store.readCommitted(scope, original.key)!!
        val acknowledgement = store.readLifetimeAcknowledgement(scope, original.key)!!
        val failure = runCatching { store.commit(scope, listOf(
            AdminMutation(before.key, before.etag, JsonObject(before.document + ("lifetime" to JsonPrimitive(900))),
                jwt("https://ta.example", "must-rollback"), lifetimePolicy = lifetimeContext(1, 900), acknowledgeLifetimePolicy = true),
            AdminMutation(AdminDocumentKey("key-preparation", "invalid-late-write"), null, doc("{}"))
        )) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(before, store.readCommitted(scope, before.key))
        assertEquals(acknowledgement, store.readLifetimeAcknowledgement(scope, before.key))
    }

    @Test fun stagedLifetimePolicyRecoversAtomicallyAndReplayCannotUndoNativeRemoval(): Unit = runBlocking {
        val original = configuration()
        val port = lifetimeProtocol()
        val workflow = DefaultAdminWorkflow(store, port)
        assertTrue(workflow.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"lifetime":50}"""), lifetimePolicy = lifetimeContext(0, explicit = true))).isOk)
        val staged = workflow.stage(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"extension":{"retained":true}}"""), lifetimePolicy = lifetimeContext(1, 900))).value
        assertEquals(0L, store.readLifetimeAcknowledgement(scope, original.key)!!.revision)
        assertEquals(JsonPrimitive(50), store.readCommitted(scope, original.key)!!.document["lifetime"])
        val restarted = SqlAdminDocumentStore(object : PublishedStatementCacheInvalidator { override suspend fun published(statementJwt: String) {} }, database)
        val recovered = DefaultAdminWorkflow(restarted, port)
        assertEquals(staged.artifact, recovered.pendingStages(scope).value.single().artifact)
        val finalized = recovered.finalizeStage(scope, staged.stageDigest!!)
        assertTrue(finalized.isOk)
        assertEquals(staged.artifact, restarted.readCommitted(scope, original.key)!!.artifact)
        assertEquals(JsonPrimitive(900), restarted.readCommitted(scope, original.key)!!.document["lifetime"])
        assertEquals(1L, restarted.readLifetimeAcknowledgement(scope, original.key)!!.revision)
        assertTrue(recovered.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"lifetime":null}"""), lifetimePolicy = lifetimeContext(1, 900, explicit = true))).isOk)
        val native = restarted.readCommitted(scope, original.key)!!
        assertTrue(recovered.finalizeStage(scope, staged.stageDigest!!).isOk)
        assertEquals(native, restarted.readCommitted(scope, original.key))
        assertFalse(native.document.containsKey("lifetime"))
        val marker = restarted.readCommitted(scope, adminLifetimeAcknowledgementKey(original.key))!!
        assertFalse(marker.document.containsKey("policyLifetimeSeconds"))
        assertFalse(native.document.containsKey("binding"))
    }

    @Test fun laterNativeCommitFencesStagedPolicyAndConcurrentPolicyWriter(): Unit = runBlocking {
        val original = configuration()
        val workflow = DefaultAdminWorkflow(store, lifetimeProtocol())
        val staged = workflow.stage(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"extension":true}"""), lifetimePolicy = lifetimeContext(1, 900))).value
        assertTrue(workflow.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"lifetime":30}"""), lifetimePolicy = lifetimeContext(1, 900, explicit = true))).isOk)
        assertEquals(412, workflow.finalizeStage(scope, staged.stageDigest!!).error.status)
        val before = store.readCommitted(scope, original.key)!!
        val results = listOf(false, true).map { explicit -> async(Dispatchers.IO) {
            workflow.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope, ifMatch = before.etag,
                document = if (explicit) doc("""{"lifetime":45}""") else doc("""{"extension":false}"""),
                lifetimePolicy = lifetimeContext(2, 1200, explicit)))
        }}.awaitAll()
        assertEquals(1, results.count { it.isOk })
        assertEquals(1, results.count { it.isErr && it.error.status == 412 })
        val winner = store.readCommitted(scope, original.key)!!
        assertEquals(winner.etag, store.readLifetimeAcknowledgement(scope, original.key)!!.documentEtag)
        assertEquals(2L, store.readLifetimeAcknowledgement(scope, original.key)!!.revision)
        assertTrue(workflow.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"lifetime":45}"""), lifetimePolicy = lifetimeContext(2, 1200, explicit = true))).isOk)
        assertEquals(JsonPrimitive(45), store.readCommitted(scope, original.key)!!.document["lifetime"])
    }

    @Test fun subordinateRecreationCannotDowngradeSamePolicyRevision(): Unit = runBlocking {
        configuration()
        val workflow = DefaultAdminWorkflow(store, lifetimeProtocol())
        val entity = "https://subordinate.example"
        val context = lifetimeContext(2, kind = AdminLifetimeKind.SUBORDINATE_STATEMENT)
        val body = doc("""{"entity_id":"$entity","status":"active"}""")
        assertTrue(workflow.execute(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(scope,
            document = body, lifetimePolicy = context)).isOk)
        assertTrue(workflow.execute(AdminOperation.DeleteAdminSubordinate, AdminCommandArgs(scope,
            resourceId = entity, lifetimePolicy = context)).isOk)
        val before = store.readCommitted(scope, AdminDocumentKey("subordinate", entity))!!
        val stale = workflow.execute(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(scope,
            document = body, lifetimePolicy = context.copy(revision = 1)))
        assertEquals(412, stale.error.status)
        assertEquals(before, store.readCommitted(scope, before.key))
        assertEquals(2L, store.readLifetimeAcknowledgement(scope, before.key)!!.revision)
    }

    @Test fun subordinateRecreationResetsDocumentIncarnationAndRetainsPolicyBinding(): Unit = runBlocking {
        configuration()
        val workflow = DefaultAdminWorkflow(store, lifetimeProtocol())
        val entity = "https://subordinate.example"
        val key = AdminDocumentKey("subordinate", entity)
        val context = lifetimeContext(5, 600, kind = AdminLifetimeKind.SUBORDINATE_STATEMENT)
        val body = doc("""{"entity_id":"$entity","status":"active"}""")
        assertTrue(workflow.execute(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(scope,
            document = body, lifetimePolicy = context)).isOk)
        val acknowledgement = store.readLifetimeAcknowledgement(scope, key)!!
        assertTrue(workflow.execute(AdminOperation.DeleteAdminSubordinate, AdminCommandArgs(scope,
            resourceId = entity, lifetimePolicy = context)).isOk)
        assertTrue(workflow.execute(AdminOperation.CreateAdminSubordinate, AdminCommandArgs(scope,
            document = body, lifetimePolicy = context.copy(revision = 6, policyLifetimeSeconds = 800))).isOk)
        val recreated = store.readLifetimeAcknowledgement(scope, key)!!
        assertNotEquals(acknowledgement.documentIncarnation, recreated.documentIncarnation)
        assertEquals("profile-one", recreated.binding.incarnationId)
        assertEquals(6L, recreated.revision)
        assertEquals(JsonPrimitive(800), store.readCommitted(scope, key)!!.document["lifetime"])
    }

    @Test fun managedRenewalAppliesPendingPolicyOnceAndRetainsAcknowledgedAbsence(): Unit = runBlocking {
        val initial = configuration()
        val port = lifetimeProtocol()
        val workflow = DefaultAdminWorkflow(store, port)
        assertTrue(workflow.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"lifetime":50}"""), lifetimePolicy = lifetimeContext(0, explicit = true))).isOk)
        var policy = lifetimeContext(1, 900)
        coEvery { port.lifetimePolicy(any(), any()) } answers { policy }
        val preparedDocuments = mutableListOf<JsonObject>()
        val futureArtifact = jwt("https://ta.example", "renewed-policy")
        coEvery { port.prepare(any(), any(), any()) } answers {
            preparedDocuments += thirdArg<JsonObject>()
            AdminPreparedArtifact(futureArtifact, adminKeyState(scope.accountId, database.jwkQueries,
                database.accountSigningKeyQueries, database.adminDocumentQueries))
        }
        val legacy = mockk<com.sphereon.openid.fed.services.command.renewal.RenewDueStatementsCommandImpl>()
        val command = AdminRenewDueStatementsCommand(testSessionExecution(), legacy, store, port, database)
        val args = com.sphereon.openid.fed.services.command.renewal.RenewDueStatementsArgs(1,
            accountId = scope.accountId, nowEpochSeconds = 100, listOnly = false)
        suspend fun renewDue() {
            val old = store.readCommitted(scope, initial.key)!!
            val dueArtifact = "header." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"exp\":1E2}".toByteArray()) + ".signature"
            assertNotNull(store.commit(scope, listOf(AdminMutation(old.key, old.etag, old.document, dueArtifact))))
            val renewed = command.execute(args)
            assertTrue(renewed.isOk)
            assertEquals(com.sphereon.openid.fed.services.command.renewal.StatementRenewalStatus.RENEWED, renewed.value.outcomes.single().status)
        }
        renewDue()
        assertEquals(JsonPrimitive(900), preparedDocuments.last()["lifetime"])
        assertEquals(1L, store.readLifetimeAcknowledgement(scope, initial.key)!!.revision)
        assertTrue(workflow.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"lifetime":null}"""), lifetimePolicy = policy.copy(explicitLifetimeWrite = true))).isOk)
        renewDue()
        assertFalse(preparedDocuments.last().containsKey("lifetime"))
        assertFalse(store.readCommitted(scope, initial.key)!!.document.containsKey("lifetime"))
        policy = lifetimeContext(2, 50) // A -> B -> A is a new policy intent despite equal historical values.
        renewDue()
        assertEquals(JsonPrimitive(50), preparedDocuments.last()["lifetime"])
        assertEquals(2L, store.readLifetimeAcknowledgement(scope, initial.key)!!.revision)
        assertEquals(futureArtifact, store.readCommitted(scope, initial.key)!!.artifact)
    }

    @Test fun managedTrustMarkStatusAcceptsNumericDatesButRejectsStringClaims(): Unit = runBlocking {
        configuration()
        val subject = "https://subject.example"
        val type = "https://types.example/numeric-date"
        val key = AdminDocumentKey("trust-mark-grant", type, subject)
        val command = com.sphereon.openid.fed.services.command.trustMark.GetTrustMarkStatusCommandImpl(testSessionExecution(),
            database.trustMarkQueries, database.adminDocumentQueries)
        for ((expiry, issuedAt, expected) in listOf(
            Triple("9.999999999E9", "1E0", "active"), Triple("9999999999.75", "1.0", "active"),
            Triple("1E0", "1", "expired"), Triple("\"9999999999\"", "1", "invalid"),
            Triple("9.999999999E9", "\"1\"", "invalid"), Triple("1e999", "1", "invalid"),
        )) {
            val payload = """{"iss":"https://ta.example","sub":"$subject","trust_mark_type":"$type","iat":$issuedAt,"exp":$expiry}"""
            val artifact = "header." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".original-signature"
            val old = store.readCommitted(scope, key)
            store.commit(scope, listOf(AdminMutation(key, old?.etag, doc("""{"status":"active"}"""), artifact)))!!
            val result = command.execute(com.sphereon.openid.fed.services.command.trustMark.GetTrustMarkStatusArgs(scope.accountId,
                com.sphereon.openid.fed.openapi.models.TrustMarkStatusRequest(subject, type, 1.0), artifact))
            assertTrue(result.isOk)
            assertEquals(expected, result.value.status.wire)
            assertEquals(artifact, result.value.trustMarkJwt)
        }
    }

    @Test fun managedRenewalSchedulesScientificAndFractionalExpiryWithoutRoundingThreshold(): Unit = runBlocking {
        val original = configuration()
        fun artifact(expiry: String) = "header." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"exp":$expiry}""".toByteArray()) + ".original-signature"
        val legacy = mockk<com.sphereon.openid.fed.services.command.renewal.RenewDueStatementsCommandImpl>()
        val protocol = mockk<AdminProtocolPort>()
        coEvery { protocol.lifetimePolicy(any(), any()) } returns null
        coEvery { legacy.execute(any()) } returns IdkResult.ok(com.sphereon.openid.fed.services.command.renewal.StatementRenewalReport(100,
            emptyList()))
        coEvery { protocol.node(any()) } returns original.document
        coEvery { protocol.prepare(any(), any(), any()) } answers { AdminPreparedArtifact(artifact("9.999999999E9"),
            adminKeyState(scope.accountId, database.jwkQueries, database.accountSigningKeyQueries, database.adminDocumentQueries)) }
        val command = AdminRenewDueStatementsCommand(testSessionExecution(), legacy, store, protocol, database)
        for ((expiry, due) in listOf("1.0075E2" to true, "101.25" to false)) {
            val old = store.readCommitted(scope, original.key)!!
            store.commit(scope, listOf(AdminMutation(old.key, old.etag, old.document, artifact(expiry))))!!
            val args = com.sphereon.openid.fed.services.command.renewal.RenewDueStatementsArgs(1, accountId = scope.accountId,
                nowEpochSeconds = 100, listOnly = true)
            val listed = command.execute(args)
            assertTrue(listed.isOk)
            assertEquals(if (due) 1 else 0, listed.value.outcomes.size)
            if (due) {
                val renewed = command.execute(args.copy(listOnly = false))
                assertTrue(renewed.isOk)
                assertEquals(com.sphereon.openid.fed.services.command.renewal.StatementRenewalStatus.RENEWED, renewed.value.outcomes.single().status)
                assertEquals(artifact("9.999999999E9"), store.readCommitted(scope, old.key)!!.artifact)
            }
        }
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
    @Test fun adoptedWorkflowUsesHostKeyAuthorityForGenerationSigningAndReload(): Unit = runBlocking {
        configuration()
        val pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val encode = java.util.Base64.getUrlEncoder().withoutPadding()
        val publicJwk = buildJsonObject {
            put("kty", "OKP"); put("crv", "Ed25519"); put("alg", "EdDSA"); put("kid", "managed-kid")
            put("x", encode.encodeToString(pair.public.encoded.takeLast(32).toByteArray()))
        }
        val app = createAdminProtocolTestGraph(this)
        val session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "host-authority-" + UUID.randomUUID(), principalType = PrincipalType.ANONYMOUS)
        val realJwt = (session.graph as JwtServiceImpl.Graph).jwtService
        var preparations = 0
        var signatures = 0
        var changeClaims = false
        var failAfterPhysicalGeneration = false
        var allowCleanup = false
        val uncommittedPhysicalKeys = mutableSetOf<String>()
        var cleanupAttempts = 0
        var existingPreparations = 0
        val material = object : AdminKeyMaterialPort {
            override suspend fun prepare(scope: AdminNodeScope, keyId: String, algorithm: String): AdminPreparedKey {
                assertEquals(this@SqlAdminDocumentStoreTest.scope.accountId, scope.accountId)
                assertEquals("EdDSA", algorithm)
                preparations++
                if (failAfterPhysicalGeneration) {
                    uncommittedPhysicalKeys += keyId
                    error("Provider completed before its public receipt was persisted")
                }
                return AdminPreparedKey(keyId, publicJwk, "managed-kid", algorithm, "krh-managed", "key-resource-managed")
            }
            override suspend fun prepareExisting(scope: AdminNodeScope, key: AdminPreparedKey, sourceKeyId: String): AdminPreparedKey {
                val pending = store.readCommitted(scope, adminKeyPreparationKey(key.id))!!
                assertEquals(JsonPrimitive("PENDING"), pending.document["status"])
                assertEquals(JsonPrimitive(false), pending.document["generated"])
                assertEquals(JsonPrimitive(sourceKeyId), pending.document["source_key_id"])
                assertNotNull(store.readKeyMaterial(scope, sourceKeyId))
                assertNull(store.readKeyMaterial(scope, key.id), "New logical key is not persisted before publication commits")
                existingPreparations++
                return key
            }
            override suspend fun sign(scope: AdminNodeScope, key: AdminPreparedKey, header: JsonObject, claims: JsonObject): String {
                assertEquals("krh-managed", key.kms)
                assertEquals("key-resource-managed", key.kmsKeyRef)
                signatures++
                val actualClaims = if (changeClaims) JsonObject(claims + ("iss" to JsonPrimitive("https://foreign.example"))) else claims
                val input = encode.encodeToString(header.toString().toByteArray()) + "." + encode.encodeToString(actualClaims.toString().toByteArray())
                val signature = java.security.Signature.getInstance("Ed25519").run {
                    initSign(pair.private); update(input.toByteArray()); sign()
                }
                return input + "." + encode.encodeToString(signature)
            }
            override suspend fun discard(scope: AdminNodeScope, keyId: String): Boolean {
                val pending = store.readCommitted(scope, adminKeyPreparationKey(keyId))!!
                assertEquals("ABORTED", pending.document.getValue("status").jsonPrimitive.content)
                assertTrue(pending.document.getValue("generated").jsonPrimitive.boolean)
                assertTrue(keyId in uncommittedPhysicalKeys, "Committed material must be retained")
                cleanupAttempts++
                if (!allowCleanup) return false
                uncommittedPhysicalKeys.remove(keyId)
                return true
            }
        }
        val resolver = mockk<TenantContextResolver>()
        coEvery { resolver.resolveIdentifier(scope.accountId) } returns scope.entityId
        val binder = mockk<OidfConfigBinder>(relaxed = true)
        val creation = mockk<CreateKeyCommandImpl>()
        coEvery { creation.prepareKey(any(), any()) } throws IllegalStateException("Generic registry cannot resolve managed resource handles")
        var publicationPolicy: AdminLifetimePolicyContext? = null
        var publicationClock = System.currentTimeMillis() / 1000
        fun protocol() = StandaloneAdminProtocolPort(store, resolver, binder, creation, realJwt,
            mockk(), mockk(), mockk(), mockk(), database, { publicationClock }, material,
            AdminLifetimePolicyResolver { _, _ -> publicationPolicy })
        val config = doc("""{"entity_id":"https://ta.example","lifetime":3600,"metadata":{"federation_entity":{"federation_fetch_endpoint":"https://ta.example/fetch","federation_list_endpoint":"https://ta.example/list"}}}""")
        var workflow = DefaultAdminWorkflow(store, protocol())
        val created = workflow.execute(AdminOperation.CreateAdminKey, AdminCommandArgs(scope, document = doc("""{"generate":{"alg":"EdDSA"}}""")))
        assertTrue(created.isOk, "Adopted administration must use the host's managed key authority")
        assertEquals(1, preparations)
        val published = workflow.execute(AdminOperation.ReplaceAdminConfiguration, AdminCommandArgs(scope, document = config))
        assertTrue(published.isOk)
        val committed = store.readCommitted(scope, AdminDocumentKey("configuration"))!!
        val parts = committed.artifact!!.split('.')
        val decoded = decodeJWTComponents(committed.artifact!!)
        assertEquals("EdDSA", decoded.header.alg)
        assertEquals("managed-kid", decoded.header.kid)
        assertEquals("entity-statement+jwt", decoded.header.typ)
        assertEquals(config.getValue("metadata"), decoded.payload["metadata"])
        assertEquals(scope.entityId, decoded.payload.getValue("iss").jsonPrimitive.content)
        assertEquals(scope.entityId, decoded.payload.getValue("sub").jsonPrimitive.content)
        assertEquals(publicJwk, decoded.payload.getValue("jwks").jsonObject.getValue("keys").jsonArray.single())
        assertTrue(decoded.payload.keys.none { it.contains("preparation") || it == "node_incarnation" })
        assertEquals("COMMITTED", store.listCommitted(scope, "key-preparation").single().document.getValue("status").jsonPrimitive.content)
        assertTrue(java.security.Signature.getInstance("Ed25519").run {
            initVerify(pair.public); update((parts[0] + "." + parts[1]).toByteArray())
            verify(java.util.Base64.getUrlDecoder().decode(parts[2]))
        })
        workflow = DefaultAdminWorkflow(store, protocol())
        val replaced = workflow.execute(AdminOperation.ReplaceAdminConfiguration, AdminCommandArgs(scope, document = config, ifMatch = committed.etag))
        assertTrue(replaced.isOk)
        assertEquals(1, preparations)
        assertEquals(2, signatures)
        assertEquals(store.readCommitted(scope, committed.key)!!.artifact,
            workflow.execute(AdminOperation.ReadAdminConfigurationStatement, AdminCommandArgs(scope)).value.artifact)
        val beforeTampering = store.readCommitted(scope, committed.key)!!
        changeClaims = true
        val tampered = workflow.execute(AdminOperation.ReplaceAdminConfiguration, AdminCommandArgs(scope, document = config, ifMatch = beforeTampering.etag))
        assertTrue(tampered.isErr)
        assertEquals(beforeTampering, store.readCommitted(scope, committed.key))
        changeClaims = false
        failAfterPhysicalGeneration = true
        val failedGeneration = workflow.execute(AdminOperation.CreateAdminKey,
            AdminCommandArgs(scope, document = doc("""{"generate":{"alg":"EdDSA"}}""")))
        assertTrue(failedGeneration.isErr)
        assertEquals(beforeTampering, store.readCommitted(scope, committed.key), "A preparation must not alter configuration ETag or publication")
        assertEquals(1, uncommittedPhysicalKeys.size)
        assertEquals(1, cleanupAttempts)
        val aborted = store.listCommitted(scope, "key-preparation").single { it.document["status"] == JsonPrimitive("ABORTED") }
        assertNull(aborted.document["cleanup_complete"])
        allowCleanup = true
        protocol().node(scope) // a fresh port's independent maintenance repairs interrupted custody
        assertTrue(uncommittedPhysicalKeys.isEmpty())
        assertEquals(2, cleanupAttempts)
        assertEquals(JsonPrimitive(true), store.readCommitted(scope, aborted.key)!!.document["cleanup_complete"])
        assertEquals(beforeTampering, store.readCommitted(scope, committed.key))
        assertEquals(1, database.jwkQueries.findAllByAccountId(scope.accountId).executeAsList().size)
        coVerify(exactly = 0) { creation.prepareKey(any(), any()) }
        // A legal new JOSE kid reuses the exact existing physical key, including pre-commit signing.
        val originalKey = database.jwkQueries.findAllByAccountId(scope.accountId).executeAsList().single()
        source.connection.use { connection -> connection.prepareStatement("UPDATE jwk SET alg = NULL WHERE id = ?").use {
            it.setObject(1, java.util.UUID.fromString(originalKey.id)); assertEquals(1, it.executeUpdate())
        } }
        assertEquals("EdDSA", store.readKeyMaterial(scope, originalKey.id)!!.key.algorithm, "Persisted public alg preserves legacy signing routes")
        val imported = JsonObject(publicJwk + ("kid" to JsonPrimitive("logical-reimport")))
        assertTrue(workflow.execute(AdminOperation.PatchAdminConfiguration, AdminCommandArgs(scope,
            document = doc("""{"lifetime":3600}"""), lifetimePolicy = lifetimeContext(0, 3600, explicit = true))).isOk)
        publicationPolicy = lifetimeContext(1, 7200)
        val rotation = workflow.execute(AdminOperation.RotateAdminKey,
            AdminCommandArgs(scope, resourceId = "managed-kid", document = buildJsonObject { put("import", imported) }))
        assertEquals(JsonPrimitive(7200), store.readCommitted(scope, committed.key)!!.document["lifetime"])
        assertEquals(1L, store.readLifetimeAcknowledgement(scope, committed.key)!!.revision)
        assertTrue(rotation.isOk, "Known-key rollover failed: $rotation")
        assertEquals(2, preparations, "Import must not generate physical key material")
        val reimported = database.jwkQueries.findAllByAccountId(scope.accountId).executeAsList().single { it.kid == "logical-reimport" }
        val reused = store.readCommitted(scope, adminKeyPreparationKey(reimported.id))!!
        assertEquals(JsonPrimitive(false), reused.document["generated"])
        assertEquals(JsonPrimitive(originalKey.id), reused.document["source_key_id"])
        assertEquals(JsonPrimitive("COMMITTED"), reused.document["status"])
        val reimportedArtifact = store.readCommitted(scope, committed.key)!!.artifact!!
        assertEquals("logical-reimport", decodeJWTComponents(reimportedArtifact).header.kid)
        val reusedParts = reimportedArtifact.split('.')
        assertTrue(java.security.Signature.getInstance("Ed25519").run {
            initVerify(pair.public); update((reusedParts[0] + "." + reusedParts[1]).toByteArray())
            verify(java.util.Base64.getUrlDecoder().decode(reusedParts[2]))
        })
        val beforeFailedImport = store.readCommitted(scope, committed.key)!!
        val discardsBeforeImport = cleanupAttempts
        changeClaims = true
        val failedImport = workflow.execute(AdminOperation.RotateAdminKey, AdminCommandArgs(scope, resourceId = "logical-reimport",
            document = buildJsonObject { put("import", JsonObject(publicJwk + ("kid" to JsonPrimitive("failed-logical-import")))) }))
        assertTrue(failedImport.isErr)
        assertEquals(beforeFailedImport, store.readCommitted(scope, committed.key))
        assertEquals(2, existingPreparations)
        assertEquals(discardsBeforeImport, cleanupAttempts, "Aborted adoption must never invoke physical discard")
        assertEquals(2, database.jwkQueries.findAllByAccountId(scope.accountId).executeAsList().size)
        val abortedImport = store.listCommitted(scope, "key-preparation").single {
            it.document["generated"] == JsonPrimitive(false) && it.document["status"] == JsonPrimitive("ABORTED")
        }
        assertEquals(JsonPrimitive(true), abortedImport.document["cleanup_complete"])
        changeClaims = false
        publicationPolicy = lifetimeContext(2, 8100)
        val selectionRevision = database.accountSigningKeyQueries.findByAccountId(scope.accountId).executeAsOne().revision
        assertTrue(workflow.selectSigningKey(scope, reimported.id, selectionRevision).isOk)
        assertEquals(JsonPrimitive(8100), store.readCommitted(scope, committed.key)!!.document["lifetime"])
        assertEquals(2L, store.readLifetimeAcknowledgement(scope, committed.key)!!.revision)
        publicationPolicy = lifetimeContext(3, 8200)
        assertTrue(workflow.execute(AdminOperation.DeleteAdminKey, AdminCommandArgs(scope, resourceId = "managed-kid")).isOk)
        assertEquals(JsonPrimitive(8200), store.readCommitted(scope, committed.key)!!.document["lifetime"])
        assertEquals(3L, store.readLifetimeAcknowledgement(scope, committed.key)!!.revision)
        publicationPolicy = lifetimeContext(4, 8300)
        val delayed = workflow.execute(AdminOperation.RotateAdminKey, AdminCommandArgs(scope, resourceId = "logical-reimport",
            document = buildJsonObject { put("import", JsonObject(publicJwk + ("kid" to JsonPrimitive("scheduled-policy-key")))); put("switch_after", 1) }))
        assertTrue(delayed.isOk)
        assertEquals(JsonPrimitive(8300), store.readCommitted(scope, committed.key)!!.document["lifetime"])
        publicationPolicy = lifetimeContext(5, 8400)
        publicationClock += 2
        workflow = DefaultAdminWorkflow(store, protocol()) // Process reconstruction resumes the scheduled publication.
        assertTrue(workflow.execute(AdminOperation.ReadAdminNode, AdminCommandArgs(scope)).isOk)
        val afterSwitch = store.readCommitted(scope, committed.key)!!
        assertEquals(JsonPrimitive(8400), afterSwitch.document["lifetime"])
        assertEquals(5L, store.readLifetimeAcknowledgement(scope, committed.key)!!.revision)
        assertEquals("scheduled-policy-key", decodeJWTComponents(afterSwitch.artifact!!).header.kid)
    }

    @Test fun standaloneProviderAttemptUsesTheSameDurableJournalForClaimAndRestartCleanup(): Unit = runBlocking {
        val configuration = configuration()
        val incarnation = adminNodeIncarnation(store, scope)
        val keyId = UUID.randomUUID().toString()
        val preparationKey = adminKeyPreparationKey(keyId)
        store.commit(scope, listOf(AdminMutation(preparationKey, null, buildJsonObject {
            put("status", "PENDING"); put("generated", true); put("account_id", scope.accountId)
            put("entity_identifier", scope.entityId!!); put("key_id", keyId); put("preparation_id", "one-provider-attempt")
            put("node_incarnation", incarnation); put("configuration_revision", configuration.etag)
            put("algorithm", "EdDSA"); put("lease_expires_at", System.currentTimeMillis() / 1000 + 300)
        })))
        val provider = "configured-aws"
        val alias = "oidfed-$keyId"
        val creation = mockk<CreateKeyCommandImpl>()
        coEvery { creation.prepareKeyWithContext(any(), any(), any()) } coAnswers {
            secondArg<suspend (String, String?) -> Unit>()(provider, alias)
            val context = thirdArg<suspend (String, String?) -> kotlin.coroutines.CoroutineContext>()(provider, alias)
            withContext(context) {
                val attempt = currentCoroutineContext()[com.sphereon.crypto.core.kms.ManagedKeyCreationAttempt]!!
                assertEquals(provider, attempt.providerId); assertEquals(alias, attempt.backingAlias)
                assertEquals(com.sphereon.crypto.core.kms.ManagedKeyCreationAttemptState.UNCLAIMED, attempt.state())
                assertEquals(1, coroutineScope { listOf(async(Dispatchers.IO) { attempt.claimCreateOnce() }, async(Dispatchers.IO) { attempt.claimCreateOnce() }).awaitAll().count { it } })
                assertEquals(com.sphereon.crypto.core.kms.ManagedKeyCreationAttemptState.CLAIMED, attempt.state())
                assertFalse(attempt.claimCreateOnce())
            }
            IdkResult.ok(PreparedFederationKey(scope.accountId, """{"kty":"OKP","crv":"Ed25519","kid":"physical","alg":"EdDSA","x":"receipt"}""",
                "physical", "EdDSA", provider, alias))
        }
        val support = DefaultAdminKeyMaterialSupport(store, creation, mockk())
        support.prepare(scope, keyId, "EdDSA")
        val claimed = store.readCommitted(scope, preparationKey)!!
        assertEquals(JsonPrimitive("one-provider-attempt"), claimed.document["generation_attempt_token"])
        assertEquals(configuration, store.readCommitted(scope, configuration.key), "Provider claims do not change config ETag or publication")
        store.commit(scope, listOf(AdminMutation(preparationKey, claimed.etag, JsonObject(claimed.document + ("status" to JsonPrimitive("ABORTED"))))))
        var providerCalls = 0
        var conclusive = false
        coEvery { creation.discardPreparedKeyWithProof(provider, alias, any(), any()) } coAnswers {
            providerCalls++
            val attempt = currentCoroutineContext()[com.sphereon.crypto.core.kms.ManagedKeyCreationAttempt]!!
            assertEquals(com.sphereon.crypto.core.kms.ManagedKeyCreationAttemptIntent.RECOVER_FOR_REVOCATION, attempt.intent)
            assertTrue(attempt.authorizeRecovery()); assertFalse(attempt.claimCreateOnce())
            if (conclusive) attempt.reportRevoked()
            IdkResult.ok(false)
        }
        assertFalse(support.discard(scope, keyId), "Unknown provider outcome stays recoverable")
        conclusive = true
        assertTrue(DefaultAdminKeyMaterialSupport(store, creation, mockk()).discard(scope, keyId))
        assertEquals(2, providerCalls)
        assertTrue(database.jwkQueries.findAllByAccountId(scope.accountId).executeAsList().isEmpty())
    }

    @Test fun privatePreparationAbortFencesDelayedPublicationWithoutChangingConfigurationRevision(): Unit = runBlocking {
        val original = configuration()
        val incarnation = adminNodeIncarnation(store, scope)
        val key = adminKeyPreparationKey(UUID.randomUUID().toString())
        // Deliberately put status last: durable recovery cannot depend on caller JSON member order.
        val pending = store.commit(scope, listOf(AdminMutation(key, null, buildJsonObject {
            put("node_incarnation", incarnation); put("generated", true); put("lease_expires_at", 1)
            put("status", "PENDING")
        })))!!.single()
        assertEquals(original, configuration())
        assertEquals(pending.key, store.recoverableKeyPreparations(scope, 1).single().key)
        val aborted = store.commit(scope, listOf(AdminMutation(key, pending.etag,
            JsonObject(pending.document + ("status" to JsonPrimitive("ABORTED"))))))!!.single()
        val delayed = store.commit(scope, listOf(
            AdminMutation(original.key, original.etag, JsonObject(original.document + ("extension" to JsonPrimitive("uncommitted")))),
            AdminMutation(key, pending.etag, JsonObject(pending.document + ("status" to JsonPrimitive("COMMITTED")))),
        ))
        assertNull(delayed, "An aborted preparation must fence the entire delayed publication transaction")
        assertEquals(original, configuration())
        store.touchKeyPreparation(scope, key.resourceId)
        assertEquals(aborted.etag, store.readCommitted(scope, key)!!.etag)
        assertEquals(incarnation, adminNodeIncarnation(store, scope))
        assertFalse(configuration().document.containsKey("key-preparation"))
        assertEquals(aborted.key, store.recoverableKeyPreparations(scope, 1).single().key)
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
        val config = doc("""{"entity_id":"https://ta.example","lifetime":3600,"metadata":{"federation_entity":{"federation_fetch_endpoint":"https://ta.example/fetch","federation_list_endpoint":"https://ta.example/list","federation_trust_mark_endpoint":"https://ta.example/trust-mark"}},"extension_claim":{"preserved":true}}""")
        var workflow = DefaultAdminWorkflow(store, protocol)
        val published = workflow.execute(AdminOperation.ReplaceAdminConfiguration, AdminCommandArgs(scope, document = config))
        assertTrue(published.isOk)
        val committed = store.readCommitted(scope, AdminDocumentKey("configuration"))!!
        val artifact = workflow.execute(AdminOperation.ReadAdminConfigurationStatement, AdminCommandArgs(scope))
        assertEquals(committed.artifact, artifact.value.artifact)
        assertEquals(firstKid, decodeJWTComponents(committed.artifact!!).header.kid)
        assertEquals(config["extension_claim"], decodeJWTComponents(committed.artifact!!).payload["extension_claim"])
        val issuance = workflow.execute(AdminOperation.CreateAdminTrustMarkType, AdminCommandArgs(scope,
            document = doc("""{"trust_mark_type":"https://ta.example/marks/signed","lifetime":900,"ref":"https://ta.example/policy","logo_uri":"https://ta.example/logo.png"}""")))
        assertTrue(issuance.isOk)
        val issued = protocol.prepare(scope, AdminDocumentKey("trust-mark-grant", "https://ta.example/marks/signed", "https://op.example"),
            doc("""{"entity_id":"https://op.example","trust_mark_type":"https://ta.example/marks/signed","status":"active"}"""))
        val issuedClaims = decodeJWTComponents(issued.artifact).payload
        assertEquals(JsonPrimitive("https://ta.example/policy"), issuedClaims["ref"])
        assertEquals(JsonPrimitive("https://ta.example/logo.png"), issuedClaims["logo_uri"])
        assertEquals(900L, issuedClaims.getValue("exp").jsonPrimitive.long - issuedClaims.getValue("iat").jsonPrimitive.long)
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
        coEvery { protocol.lifetimePolicy(any(), any()) } returns null
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
