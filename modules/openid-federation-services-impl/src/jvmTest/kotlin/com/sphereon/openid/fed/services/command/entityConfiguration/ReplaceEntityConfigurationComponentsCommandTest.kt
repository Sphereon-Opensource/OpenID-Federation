package com.sphereon.openid.fed.services.command.entityConfiguration

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.asCoreApiServiceGraph
import com.sphereon.di.context.PrincipalType
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.persistence.Database
import com.sphereon.openid.fed.persistence.database.JavaUuidStringAdapter
import com.sphereon.openid.fed.persistence.models.Account
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.AuthorityHint
import com.sphereon.openid.fed.persistence.models.AuthorityHintQueries
import com.sphereon.openid.fed.persistence.models.Metadata
import com.sphereon.openid.fed.persistence.models.MetadataQueries
import com.sphereon.openid.fed.services.command.resolution.ExactSuperiorEvidenceTestGraph
import com.sphereon.openid.fed.services.command.resolution.createExactSuperiorEvidenceTestGraph
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real migrated SQLDelight queries and a dedicated PostgreSQL container. No global Persistence or
 * default localhost datasource is touched through the internal command constructor.
 */
class ReplaceEntityConfigurationComponentsCommandTest {
    companion object {
        private var postgres: PostgreSQLContainer<Nothing>? = null

        @JvmStatic @BeforeClass
        fun startPostgres() {
            assertTrue(
                runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
                "Atomic component replacement proof requires Docker and a live PostgreSQL container",
            )
            postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                withDatabaseName("oidfed_atomic_components")
                withUsername("test")
                withPassword("test")
                start()
            }
        }

        @JvmStatic @AfterClass
        fun stopPostgres() {
            postgres?.stop()
            postgres = null
        }
    }

    private lateinit var driver: SqlDriver
    private lateinit var source: PGSimpleDataSource
    private lateinit var schemaJdbcUrl: String
    private lateinit var dbUsername: String
    private lateinit var dbPassword: String
    private lateinit var accountQueries: AccountQueries
    private lateinit var metadataQueries: MetadataQueries
    private lateinit var authorityHintQueries: AuthorityHintQueries
    private lateinit var app: ExactSuperiorEvidenceTestGraph
    private lateinit var execution: SessionExecution

    private val oldFederation = Json.parseToJsonElement("""{"organization_name":"Old operator"}""").jsonObject
    private val newFederation = Json.parseToJsonElement("""{"organization_name":"New operator"}""").jsonObject
    private val authorizationServer = Json.parseToJsonElement(
        """{"issuer":"https://entity.example/as","response_types_supported":["code"]}"""
    ).jsonObject
    private val unrelatedIssuer = Json.parseToJsonElement(
        """{"credential_issuer":"https://other.example/issuer","credential_endpoint":"https://other.example/credential"}"""
    ).jsonObject

    @Before
    fun setUp() {
        val container = requireNotNull(postgres)
        val schema = "aggregate_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        val separator = if ('?' in container.jdbcUrl) '&' else '?'
        schemaJdbcUrl = container.jdbcUrl + separator + "currentSchema=" + schema
        dbUsername = container.username
        dbPassword = container.password
        source = actorSource("atomic-setup-" + UUID.randomUUID().toString().replace("-", ""))
        driver = source.asJdbcDriver()
        Database.Schema.migrate(driver, 0L, Database.Schema.version)
        accountQueries = AccountQueries(driver, Account.Adapter(JavaUuidStringAdapter))
        metadataQueries = MetadataQueries(
            driver, Metadata.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter)
        )
        authorityHintQueries = AuthorityHintQueries(
            driver, AuthorityHint.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter)
        )
        app = createExactSuperiorEvidenceTestGraph(this)
        execution = newExecution()
    }

    private fun newExecution(): SessionExecution {
        val session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "replace-components-" + UUID.randomUUID(),
            principalType = PrincipalType.ANONYMOUS,
        )
        return session.asCoreApiServiceGraph().serviceExecution
   }

    private fun actorSource(actor: String): PGSimpleDataSource = PGSimpleDataSource().apply {
        setUrl(schemaJdbcUrl + "&ApplicationName=" + actor)
        user = dbUsername
        password = dbPassword
    }

    @After
    fun tearDown() {
        if (::driver.isInitialized) driver.close()
    }

    private data class StoredRows(
        val metadata: List<Metadata>,
        val authorityHints: List<AuthorityHint>,
    )

    private fun rows(accountId: String) = StoredRows(
        metadataQueries.findByAccountId(accountId).executeAsList().sortedBy { it.id },
        authorityHintQueries.findByAccountId(accountId).executeAsList().sortedBy { it.id },
    )

    private fun activeMetadata(accountId: String): Map<String, JsonObject> =
        metadataQueries.findByAccountId(accountId).executeAsList().associate {
            it.key to Json.parseToJsonElement(it.metadata).jsonObject
        }

    private fun account(identifier: String): String =
        accountQueries.create("account-" + UUID.randomUUID(), identifier).executeAsOne().id

    private fun seed(accountId: String, role: String, body: JsonObject, hint: String) {
        metadataQueries.create(accountId, role, body.toString()).executeAsOne()
        authorityHintQueries.create(accountId, hint).executeAsOne()
    }

    private fun command() = ReplaceEntityConfigurationComponentsCommandImpl(
        execution, accountQueries, metadataQueries, authorityHintQueries,
    )

    private fun command(driver: SqlDriver, execution: SessionExecution) =
        ReplaceEntityConfigurationComponentsCommandImpl(
            execution,
            AccountQueries(driver, Account.Adapter(JavaUuidStringAdapter)),
            MetadataQueries(driver, Metadata.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter)),
            AuthorityHintQueries(driver, AuthorityHint.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter)),
        )

    @Test
    fun replacementStoresExactlyDesiredRolesAndHintsWithoutTouchingAnotherAccount() = runTest {
        val selected = account("https://entity.example/as")
        val unrelated = account("https://other.example/issuer")
        seed(selected, "federation_entity", oldFederation, "https://old-superior.example")
        seed(selected, "openid_provider", oldFederation, "https://shared-superior.example")
        seed(unrelated, "openid_credential_issuer", unrelatedIssuer, "https://other-superior.example")
        val unrelatedBefore = rows(unrelated)
        val desired = mapOf(
            "federation_entity" to newFederation,
            "oauth_authorization_server" to authorizationServer,
        )
        val hints = listOf("https://new-superior.example", "https://shared-superior.example")

        val result = command().execute(ReplaceEntityConfigurationComponentsArgs(
            selected, "https://entity.example/as", desired, hints,
        ))

        assertTrue(result.isOk, "the selected account must receive the complete replacement")
        assertEquals(selected, result.value.accountId)
        assertEquals("https://entity.example/as", result.value.entityIdentifier)
        assertEquals(desired, result.value.metadata)
        assertEquals(desired.size, result.value.metadata.size)
        assertEquals(hints.toSet(), result.value.authorityHints.toSet())
        assertEquals(hints.size, result.value.authorityHints.size)
        assertEquals(desired, activeMetadata(selected))
        assertEquals(desired.size, rows(selected).metadata.size)
        assertEquals(hints.toSet(), rows(selected).authorityHints.map { it.identifier }.toSet())
        assertEquals(hints.size, rows(selected).authorityHints.size)
        assertEquals(unrelatedBefore, rows(unrelated), "an unrelated account's full rows must be unchanged")
    }

    @Test
    fun emptyReplacementClearsBothComponentCollections() = runTest {
        val selected = account("https://entity.example/as")
        seed(selected, "federation_entity", oldFederation, "https://old-superior.example")

        val result = command().execute(ReplaceEntityConfigurationComponentsArgs(
            selected, "https://entity.example/as", emptyMap(), emptyList(),
        ))

        assertTrue(result.isOk, "empty collections are an explicit clear, not a no-op")
        assertEquals(emptyMap<String, JsonObject>(), result.value.metadata)
        assertEquals(emptyList<String>(), result.value.authorityHints)
        assertEquals(0, result.value.metadata.size)
        assertEquals(0, result.value.authorityHints.size)
        assertEquals(emptyList<Metadata>(), rows(selected).metadata)
        assertEquals(emptyList<AuthorityHint>(), rows(selected).authorityHints)
    }

    @Test
    fun exactIdentifierMismatchRejectsBeforeAnyComponentMutation() = runTest {
        val selected = account("https://entity.example/Case/%2F")
        seed(selected, "federation_entity", oldFederation, "https://old-superior.example")
        val before = rows(selected)

        val result = command().execute(ReplaceEntityConfigurationComponentsArgs(
            selected, "https://entity.example/case/%2F",
            mapOf("federation_entity" to newFederation), listOf("https://new-superior.example"),
        ))

        assertTrue(result.isErr)
        assertIs<InvalidRequestError>(result.error)
        assertEquals(before, rows(selected), "wrong exact identifier must preserve entire stored rows")
    }

    @Test
    fun missingAccountRejectsWithoutCreatingComponents() = runTest {
        val absent = UUID.randomUUID().toString()

        val result = command().execute(ReplaceEntityConfigurationComponentsArgs(
            absent, "https://missing.example",
            mapOf("federation_entity" to newFederation), listOf("https://new-superior.example"),
        ))

        assertTrue(result.isErr)
        assertIs<AccountNotFoundError>(result.error)
        assertNull(accountQueries.findById(absent).executeAsOneOrNull())
        assertEquals(emptyList<Metadata>(), rows(absent).metadata)
        assertEquals(emptyList<AuthorityHint>(), rows(absent).authorityHints)
    }

    @Test
    fun softDeletedAccountRejectsAndPreservesItsExistingComponents() = runTest {
        val selected = account("https://deleted.example/Entity")
        seed(selected, "federation_entity", oldFederation, "https://old-superior.example")
        val before = rows(selected)
        assertNotNull(accountQueries.delete(selected).executeAsOneOrNull())
        assertNull(accountQueries.findById(selected).executeAsOneOrNull())

        val result = command().execute(ReplaceEntityConfigurationComponentsArgs(
            selected, "https://deleted.example/Entity",
            mapOf("federation_entity" to newFederation), listOf("https://new-superior.example"),
        ))

        assertTrue(result.isErr)
        assertIs<AccountNotFoundError>(result.error)
        assertEquals(before, rows(selected))
    }

    @Test
    fun failedHintWriteRollsBackAlreadyMutatedMetadataAndOldHints() = runTest {
        val selected = account("https://rollback.example/Entity")
        seed(selected, "federation_entity", oldFederation, "https://old-superior.example")
        val before = rows(selected)
        source.connection.use { control ->
            control.executeSql("CREATE SEQUENCE rollback_probe")
            control.executeSql("""
                CREATE FUNCTION reject_candidate_hint() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN
                  IF NOT EXISTS (
                    SELECT 1 FROM Metadata
                    WHERE account_id = NEW.account_id AND key = 'federation_entity'
                      AND deleted_at IS NULL AND metadata LIKE '%Rollback candidate%'
                  ) THEN
                    RAISE EXCEPTION 'candidate metadata was not written before hint insertion';
                  END IF;
                  PERFORM nextval('rollback_probe');
                  RAISE EXCEPTION 'injected hint insertion failure';
                  RETURN NEW;
                END
                ${'$'}${'$'}
            """.trimIndent())
            control.executeSql("""
                CREATE TRIGGER reject_candidate_hint BEFORE INSERT ON AuthorityHint
                FOR EACH ROW WHEN (NEW.identifier = 'https://reject-superior.example')
                EXECUTE FUNCTION reject_candidate_hint()
            """.trimIndent())

            val candidate = Json.parseToJsonElement(
                """{"organization_name":"Rollback candidate"}"""
            ).jsonObject
            val result = command().execute(ReplaceEntityConfigurationComponentsArgs(
                selected,
                "https://rollback.example/Entity",
                mapOf("federation_entity" to candidate),
                listOf("https://reject-superior.example"),
            ))

            assertTrue(result.isErr, "the schema-local write failure must be controlled")
            assertTrue(control.rollbackProbeFired(), "the trigger must observe the candidate metadata write")
            assertEquals(before, rows(selected), "the failed transaction must restore full old rows")
        }
    }

    @Test
    fun sameAccountReplacementsSerializeWhileIndependentAccountProceeds() {
        val selected = account("https://race.example/Entity")
        val independent = account("https://independent.example/Entity")
        val metadataA = Json.parseToJsonElement(
            """{"organization_name":"Candidate A"}"""
        ).jsonObject
        val metadataB = Json.parseToJsonElement(
            """{"organization_name":"Candidate B"}"""
        ).jsonObject
        val argsA = ReplaceEntityConfigurationComponentsArgs(
            selected, "https://race.example/Entity",
            mapOf("federation_entity" to metadataA, "oauth_authorization_server" to authorizationServer),
            listOf("https://a-superior.example", "https://a-second.example"),
        )
        val argsB = ReplaceEntityConfigurationComponentsArgs(
            selected, "https://race.example/Entity",
            mapOf("federation_entity" to metadataB, "openid_credential_issuer" to unrelatedIssuer),
            listOf("https://b-superior.example", "https://b-second.example"),
        )
        val argsIndependent = ReplaceEntityConfigurationComponentsArgs(
            independent, "https://independent.example/Entity",
            mapOf("federation_entity" to newFederation),
            listOf("https://independent-superior.example"),
        )
        val runId = UUID.randomUUID().toString().replace("-", "")
        val actorA = "atomic-a-$runId"
        val actorB = "atomic-b-$runId"
        val actorIndependent = "atomic-independent-$runId"
        val actorControl = "atomic-control-$runId"
        val driverA = actorSource(actorA).asJdbcDriver()
        val driverB = actorSource(actorB).asJdbcDriver()
        val driverIndependent = actorSource(actorIndependent).asJdbcDriver()
        val executionA = newExecution()
        val executionB = newExecution()
        val executionIndependent = newExecution()
        val control = actorSource(actorControl).connection
        val controlPid = control.backendPid()
        val workers = Executors.newFixedThreadPool(3)
        var advisoryHeld = false
        var futureA: CompletableFuture<IdkResult<ReplacedEntityConfigurationComponents, FederationError>>? = null
        var futureB: CompletableFuture<IdkResult<ReplacedEntityConfigurationComponents, FederationError>>? = null
        var futureIndependent: CompletableFuture<IdkResult<ReplacedEntityConfigurationComponents, FederationError>>? = null
        try {
            // A pauses at its first candidate INSERT. The selected account starts empty, so an
            // implementation without the account-row lock cannot inherit a component-row lock.
            control.executeSql("""
                CREATE FUNCTION pause_candidate_a() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN
                  IF NEW.metadata LIKE '%Candidate A%' THEN
                    PERFORM pg_advisory_lock(739273::bigint);
                    PERFORM pg_advisory_unlock(739273::bigint);
                  END IF;
                  RETURN NEW;
                END
                ${'$'}${'$'}
            """.trimIndent())
            control.executeSql("""
                CREATE TRIGGER pause_candidate_a BEFORE INSERT ON Metadata
                FOR EACH ROW EXECUTE FUNCTION pause_candidate_a()
            """.trimIndent())
            control.executeSql("SELECT pg_advisory_lock(739273::bigint)")
            advisoryHeld = true

            futureA = CompletableFuture.supplyAsync({
                runBlocking { command(driverA, executionA).execute(argsA) }
            }, workers)
            var aPid: Int? = null
            control.awaitDbState("A's real metadata-insert advisory wait") {
                aPid = waitingActorPid(actorA, "advisory", "Metadata")
                futureA!!.isDone || aPid != null
            }
            assertFalse(futureA!!.isDone, "A must reach the trigger rather than return before a write")
            val observedAPid = requireNotNull(aPid)
            assertTrue(observedAPid != controlPid)
            assertTrue(control.isBlockedBy(observedAPid, controlPid), "control must block A's advisory wait")

            futureB = CompletableFuture.supplyAsync({
                runBlocking { command(driverB, executionB).execute(argsB) }
            }, workers)
            futureIndependent = CompletableFuture.supplyAsync({
                runBlocking { command(driverIndependent, executionIndependent).execute(argsIndependent) }
            }, workers)
            var bPid: Int? = null
            control.awaitDbState("B's actual account-row lock wait or premature completion") {
                bPid = waitingActorPid(actorB, "transactionid", "Account")
                    ?: waitingActorPid(actorB, "tuple", "Account")
                futureB!!.isDone || bPid != null
            }
            assertFalse(futureB!!.isDone, "B must not finish while A owns the selected account lock")
            val observedBPid = requireNotNull(bPid)
            assertTrue(observedBPid != observedAPid && observedBPid != controlPid)
            assertTrue(control.isBlockedBy(observedBPid, observedAPid),
                "B's observed Account-row wait must be blocked by A, not an unrelated backend")
            val independentResult = futureIndependent!!.get(15, TimeUnit.SECONDS)
            assertTrue(independentResult.isOk, "an unrelated account must proceed while A is paused")
            assertEquals(argsIndependent.metadata, activeMetadata(independent))
            assertEquals(argsIndependent.authorityHints, rows(independent).authorityHints.map { it.identifier })

            control.executeSql("SELECT pg_advisory_unlock(739273::bigint)")
            advisoryHeld = false
            val resultA = futureA!!.get(15, TimeUnit.SECONDS)
            val resultB = futureB!!.get(15, TimeUnit.SECONDS)
            assertTrue(resultA.isOk, "A must complete its whole candidate: $resultA")
            assertTrue(resultB.isOk, "B must replace A's whole candidate: $resultB")
            assertEquals(argsB.metadata, activeMetadata(selected))
            assertEquals(argsB.metadata.size, rows(selected).metadata.size)
            assertEquals(argsB.authorityHints.toSet(), rows(selected).authorityHints.map { it.identifier }.toSet())
            assertEquals(argsB.authorityHints.size, rows(selected).authorityHints.size)
        } finally {
            if (advisoryHeld) runCatching { control.executeSql("SELECT pg_advisory_unlock(739273::bigint)") }
            runCatching { futureA?.get(5, TimeUnit.SECONDS) }
            runCatching { futureB?.get(5, TimeUnit.SECONDS) }
            runCatching { futureIndependent?.get(5, TimeUnit.SECONDS) }
            workers.shutdownNow()
            control.close()
            driverA.close()
            driverB.close()
            driverIndependent.close()
        }
    }

    private fun Connection.executeSql(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.rollbackProbeFired(): Boolean =
        createStatement().use { statement ->
            statement.executeQuery("SELECT is_called FROM rollback_probe").use { result ->
                assertTrue(result.next())
                result.getBoolean(1)
            }
        }

    private fun Connection.backendPid(): Int =
        createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { result ->
                assertTrue(result.next())
                result.getInt(1)
            }
        }

    private fun Connection.waitingActorPid(actor: String, event: String, table: String): Int? =
        prepareStatement("""
            SELECT pid FROM pg_stat_activity
            WHERE datname = current_database() AND application_name = ?
              AND wait_event_type = 'Lock' AND wait_event = ? AND query ILIKE ?
            LIMIT 1
        """.trimIndent()).use { statement ->
            statement.setString(1, actor)
            statement.setString(2, event)
            statement.setString(3, "%$table%")
            statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else null }
        }

    private fun Connection.isBlockedBy(waitingPid: Int, blockingPid: Int): Boolean =
        prepareStatement("SELECT ? = ANY(pg_blocking_pids(?))").use { statement ->
            statement.setInt(1, blockingPid)
            statement.setInt(2, waitingPid)
            statement.executeQuery().use { result ->
                assertTrue(result.next())
                result.getBoolean(1)
            }
        }

    private fun Connection.awaitDbState(label: String, predicate: Connection.() -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(10) // Poll only; PostgreSQL's actual wait state is the proof.
        }
        error("Timed out waiting for $label")
    }
}
