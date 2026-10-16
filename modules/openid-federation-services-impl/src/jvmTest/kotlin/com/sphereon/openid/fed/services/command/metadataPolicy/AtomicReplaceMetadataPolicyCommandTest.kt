package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.openid.fed.services.command.entityConfiguration.*

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.asCoreApiServiceGraph
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.kms.asKeyManagerServiceGraph
import com.sphereon.crypto.jose.jws.JwsCompact
import com.sphereon.crypto.jose.jws.JwtCompactResult
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.crypto.jose.jws.command.CreateJwsArgs
import com.sphereon.crypto.jose.jws.command.VerifyJwsArgs
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderConfig
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderFactoryImpl
import com.sphereon.di.context.PrincipalType
import com.sphereon.di.session.SessionInstance
import com.sphereon.openid.fed.account.mappers.toDTO as toAccountDTO
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.SelectedSigningKeyConflictError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.persistence.Database
import com.sphereon.openid.fed.persistence.database.JavaUuidStringAdapter
import com.sphereon.openid.fed.persistence.models.Account
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.AccountSigningKey
import com.sphereon.openid.fed.persistence.models.AccountSigningKeyQueries
import com.sphereon.openid.fed.persistence.models.Jwk
import com.sphereon.openid.fed.persistence.models.JwkQueries
import com.sphereon.openid.fed.services.AccountRepository
import com.sphereon.openid.fed.services.JwkService
import com.sphereon.openid.fed.services.command.resolution.createExactSuperiorEvidenceTestGraph
import com.sphereon.openid.fed.services.command.resolution.ExactSuperiorEvidenceTestGraph
import com.sphereon.openid.fed.services.command.jwk.RevokeKeyArgs
import com.sphereon.openid.fed.services.command.jwk.RevokeKeyCommandImpl
import com.sphereon.openid.fed.services.command.jwk.SetAccountSigningKeySelectionArgs
import com.sphereon.openid.fed.services.command.jwk.AccountSigningKeySelection
import com.sphereon.openid.fed.services.command.jwk.SetAccountSigningKeySelectionCommandImpl
import com.sphereon.openid.fed.services.command.jwk.FindAccountSigningKeySelectionArgs
import com.sphereon.openid.fed.services.command.jwk.FindAccountSigningKeySelectionCommandImpl
import com.sphereon.openid.fed.services.mappers.toDTO
import com.sphereon.openid.fed.services.signPayload
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.openapi.models.TenantJwk
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import java.util.IdentityHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import java.sql.SQLException
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dedicated migrated PostgreSQL rows and real software-KMS JWTs. Only account/key service
 * aggregation is test-local; it reads the same SQLDelight rows that the command must validate.
 */
class AtomicReplaceMetadataPolicyCommandTest {
    companion object {
        private var postgres: PostgreSQLContainer<Nothing>? = null

        @JvmStatic @BeforeClass
        fun startPostgres() {
            assertTrue(
                runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
                "Prepared signing proof requires Docker and an isolated PostgreSQL container",
            )
            postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                withDatabaseName("oidfed_prepared_signing")
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

    private val identifier = "https://entity.example/case/%2F"
    private lateinit var driver: SqlDriver
    private lateinit var source: HikariDataSource
    private lateinit var schemaJdbcUrl: String
    private lateinit var dbUsername: String
    private lateinit var dbPassword: String
    private lateinit var ownedSession: SessionInstance
    private val actorSessions = mutableListOf<SessionInstance>()
    private lateinit var accountQueries: AccountQueries
    private lateinit var jwkQueries: JwkQueries
    private lateinit var accountSigningKeyQueries: AccountSigningKeyQueries
    private lateinit var execution: SessionExecution
    private lateinit var keyManager: com.sphereon.crypto.core.kms.KeyManagerService
    private lateinit var jwtService: JwtService
    private lateinit var recordingJwt: CountingJwtService
    private lateinit var accountRepository: AccountRepository
    private lateinit var jwkService: JwkService
    private lateinit var app: ExactSuperiorEvidenceTestGraph

    private class CountingJwtService(private val delegate: JwtService) : JwtService by delegate {
        var signCalls = 0
        var afterSigning: (suspend () -> Unit)? = null
        override suspend fun createJwsCompact(args: CreateJwsArgs): IdkResult<JwtCompactResult, com.sphereon.core.api.error.IdkError> {
            signCalls++
            val result = delegate.createJwsCompact(args)
            afterSigning?.invoke()
            return result
        }
    }

    @Before
    fun setUp() {
        val container = requireNotNull(postgres)
        val schema = "prepared_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        val separator = if ('?' in container.jdbcUrl) '&' else '?'
        schemaJdbcUrl = container.jdbcUrl + separator + "currentSchema=" + schema
        dbUsername = container.username
        dbPassword = container.password
        source = HikariDataSource(HikariConfig().apply {
            jdbcUrl = schemaJdbcUrl
            username = container.username
            password = container.password
            maximumPoolSize = 6
            minimumIdle = 1
            poolName = "prepared-$schema"
        })
        driver = source.asJdbcDriver()
        Database.Schema.migrate(driver, 0L, Database.Schema.version)
        accountQueries = AccountQueries(driver, Account.Adapter(JavaUuidStringAdapter))
        jwkQueries = JwkQueries(driver, Jwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter))
        accountSigningKeyQueries = AccountSigningKeyQueries(
            driver,
            AccountSigningKey.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
        )

        app = createExactSuperiorEvidenceTestGraph(this)
        val session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "prepared-signer-" + UUID.randomUUID(), principalType = PrincipalType.ANONYMOUS,
        )
        ownedSession = session
        execution = session.asCoreApiServiceGraph().serviceExecution
        val provider = (app as SoftwareKmsProviderFactoryImpl.Graph).softwareKmsProvider.create(
            SoftwareKmsProviderConfig(id = "prepared-provider-" + UUID.randomUUID()),
            execution,
        )
        keyManager = session.graph.asKeyManagerServiceGraph().keyManagerService
        keyManager.registerProvider(provider, makeDefaultKms = true)
        jwtService = (session.graph as com.sphereon.crypto.jose.jws.JwtServiceImpl.Graph).jwtService
        recordingJwt = CountingJwtService(jwtService)

        accountRepository = mockk()
        coEvery { accountRepository.findById(any()) } coAnswers {
            IdkResult.ok(accountQueries.findById(firstArg()).executeAsOneOrNull()?.toAccountDTO())
        }
        jwkService = mockk()
        coEvery { jwkService.getKeys(any(), false) } coAnswers {
            IdkResult.ok(jwkQueries.findByAccountId(firstArg()).executeAsList().map { it.toDTO() }.toTypedArray())
        }
    }

    @After
    fun tearDown() {
        val failures = buildList {
            actorSessions.forEach { actor -> runCatching { actor.destroy() }.exceptionOrNull()?.let(::add) }
            runCatching { if (::ownedSession.isInitialized) ownedSession.destroy() }.exceptionOrNull()?.let(::add)
            runCatching { if (::app.isInitialized) app.destroy() }.exceptionOrNull()?.let(::add)
            runCatching { if (::driver.isInitialized) driver.close() }.exceptionOrNull()?.let(::add)
            runCatching { if (::source.isInitialized) source.close() }.exceptionOrNull()?.let(::add)
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private fun account(entityIdentifier: String = identifier): String =
        accountQueries.create("prepared-" + UUID.randomUUID(), entityIdentifier).executeAsOne().id

    private suspend fun key(accountId: String, algorithm: SignatureAlgorithm = SignatureAlgorithm.ECDSA_SHA256, joseAlgorithm: String = "ES256"): Jwk {
        val generated = keyManager.generateKeyAsync(alg = algorithm)
        return jwkQueries.create(
            account_id = accountId,
            alg = joseAlgorithm,
            kid = requireNotNull(generated.kid),
            kms = generated.providerId,
            kms_key_ref = requireNotNull(generated.alias),
            key = generated.jose.publicJwk.toJsonString(),
        ).executeAsOne()
    }



    private val role = "openid_relying_party"
    private val bodyA = Json.parseToJsonElement("""{"organization_name":{"default":"A"}}""").jsonObject
    private val bodyB = Json.parseToJsonElement("""{"organization_name":{"default":"B"}}""").jsonObject
    private fun policies() = com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries(driver,
        com.sphereon.openid.fed.persistence.models.MetadataPolicy.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter))
    private fun revisions() = com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevisionQueries(driver,
        com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevision.Adapter(JavaUuidStringAdapter))
    private fun replace() = AtomicReplaceMetadataPolicyCommandImpl(execution, accountQueries, policies(), revisions())
    private fun read() = GetAccountMetadataPolicySnapshotCommandImpl(execution, accountQueries, policies(), revisions())
    private fun request(account: String, revision: Long, generation: Long, expected: JsonObject?, desired: JsonObject?, token: String = UUID.randomUUID().toString()) =
        AtomicReplaceMetadataPolicyArgs(account, identifier, role, revision, generation, token, expected, desired)
    private fun rows(): Map<String, List<String>> = source.connection.use { connection ->
        listOf("Account", "MetadataPolicy", "AccountMetadataPolicyRevision").associateWith { table ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT row_to_json(t)::text FROM $table AS t ORDER BY row_to_json(t)::text").use { result ->
                    buildList { while (result.next()) add(result.getString(1)) }
                }
            }
        }
    }
    @Test fun legacyPolicyReadsAtGenuineRevisionZeroWithoutWrites() = runTest {
        val account = account(); policies().create(account, role, bodyB.toString()).executeAsOne()
        val before = rows(); val result = read().execute(GetAccountMetadataPolicySnapshotArgs(account, identifier, role))
        assertTrue(result.isOk); assertEquals(0L, result.value.revision); assertEquals(bodyB, result.value.policy)
        assertNull(result.value.operationGeneration); assertEquals(before, rows())
    }
    @Test fun atomicReplacementPreservesOtherRolesAndRecordsActualRevision() = runTest {
        val account = account(); policies().create(account, role, bodyB.toString()).executeAsOne()
        val other = policies().create(account, "openid_provider", bodyA.toString()).executeAsOne()
        val result = replace().execute(request(account, 0L, 11L, bodyB, bodyA))
        assertTrue(result.isOk); assertEquals(1L, result.value.revision); assertEquals(bodyA, result.value.policy)
        assertEquals(11L, result.value.operationGeneration)
        assertEquals(other, policies().findById(other.id).executeAsOne())
        assertEquals(1, policies().findByAccountIdAndKey(account, role).executeAsList().size)
    }
    @Test fun sameLatestOperationIsIdempotentWithoutAnyRowChange() = runTest {
        val account = account(); val request = request(account, 0L, 11L, null, bodyA)
        val first = replace().execute(request); assertTrue(first.isOk); val before = rows()
        val repeated = replace().execute(request); assertTrue(repeated.isOk); assertEquals(first.value, repeated.value)
        assertEquals(before, rows())
    }
    @Test fun delayedOldOperationCannotOverwriteWinnerAfterAba() = runTest {
        val account = account(); policies().create(account, role, bodyB.toString()).executeAsOne()
        val old = request(account, 0L, 11L, bodyB, bodyA)
        assertTrue(replace().execute(old).isOk)
        assertTrue(replace().execute(request(account, 1L, 13L, bodyA, bodyB)).isOk)
        val winner = rows(); assertTrue(replace().execute(old).isErr); assertEquals(winner, rows())
        val current = read().execute(GetAccountMetadataPolicySnapshotArgs(account, identifier, role))
        assertTrue(current.isOk); assertEquals(bodyB, current.value.policy); assertEquals(2L, current.value.revision)
    }
    @Test fun sameGenerationDifferentPayloadOrTokenRefuses() = runTest {
        val account = account(); val old = request(account, 0L, 11L, null, bodyA)
        assertTrue(replace().execute(old).isOk); val before = rows()
        assertTrue(replace().execute(old.copy(expectedRevision = 1L, expectedPolicy = bodyA, policy = bodyB)).isErr)
        assertTrue(replace().execute(old.copy(operationToken = UUID.randomUUID().toString())).isErr)
        assertEquals(before, rows())
    }
    @Test fun olderGenerationRefusesEvenWhenCurrentBodyMatches() = runTest {
        val account = account(); assertTrue(replace().execute(request(account, 0L, 11L, null, bodyB)).isOk)
        val before = rows(); assertTrue(replace().execute(request(account, 1L, 10L, bodyB, bodyA)).isErr)
        assertEquals(before, rows())
    }
    @Test fun deletingPolicyRetainsDurableRoleRevisionAndOtherRoles() = runTest {
        val account = account(); val other = policies().create(account, "openid_provider", bodyA.toString()).executeAsOne()
        assertTrue(replace().execute(request(account, 0L, 11L, null, bodyA)).isOk)
        val result = replace().execute(request(account, 1L, 13L, bodyA, null)); assertTrue(result.isOk)
        assertEquals(2L, result.value.revision); assertNull(result.value.policy)
        assertEquals(other, policies().findById(other.id).executeAsOne())
    }
    @Test fun incorrectAccountIdentityRefusesWithoutWrites() = runTest {
        val account = account(); val before = rows()
        assertTrue(replace().execute(request(account, 0L, 11L, null, bodyA).copy(expectedEntityIdentifier = "https://foreign.example")).isErr)
        assertEquals(before, rows())
    }
    @Test fun malformedPolicyAndInvalidCoordinatesRefuseWithoutWrites() = runTest {
        val account = account(); val before = rows()
        assertTrue(replace().execute(request(account, -1L, 11L, null, bodyA)).isErr)
        assertTrue(replace().execute(request(account, 0L, 0L, null, bodyA)).isErr)
        assertTrue(replace().execute(request(account, 0L, 11L, null, bodyA, "")).isErr)
        assertTrue(replace().execute(request(account, 0L, 11L, null,
            Json.parseToJsonElement("""{"organization_name":{"one_of":"invalid"}}""").jsonObject)).isErr)
        assertEquals(before, rows())
    }
    @Test fun failingRevisionWriteRollsBackDeletedPolicyAndNewPolicyTogether() = runTest {
        val account = account(); assertTrue(replace().execute(request(account, 0L, 11L, null, bodyB)).isOk)
        source.connection.use { connection -> connection.createStatement().use { statement ->
            statement.execute("CREATE FUNCTION refuse_policy_revision() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''injected revision write refusal''; END'")
            statement.execute("CREATE TRIGGER refuse_policy_revision BEFORE UPDATE ON AccountMetadataPolicyRevision FOR EACH ROW EXECUTE FUNCTION refuse_policy_revision()")
        } }
        val before = rows(); assertTrue(replace().execute(request(account, 1L, 13L, bodyB, bodyA)).isErr)
        assertEquals(before, rows())
    }

    private fun actorExecution(): SessionExecution {
        val session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "policy-actor-" + UUID.randomUUID(), principalType = PrincipalType.ANONYMOUS)
        actorSessions += session
        return session.asCoreApiServiceGraph().serviceExecution
    }
    @Test fun independentConnectionsRaceWithOneActualWinnerAndOneRefusal() = runTest {
        val account = account(); val secondDriver = source.asJdbcDriver()
        try {
            val secondAccounts = AccountQueries(secondDriver, Account.Adapter(JavaUuidStringAdapter))
            val secondPolicies = com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries(secondDriver,
                com.sphereon.openid.fed.persistence.models.MetadataPolicy.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter))
            val secondRevisions = com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevisionQueries(secondDriver,
                com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevision.Adapter(JavaUuidStringAdapter))
            val firstCommand = replace()
            val secondCommand = AtomicReplaceMetadataPolicyCommandImpl(actorExecution(), secondAccounts, secondPolicies, secondRevisions)
            val start = CompletableDeferred<Unit>()
            val first = async(kotlinx.coroutines.Dispatchers.IO) { start.await(); firstCommand.execute(request(account, 0L, 11L, null, bodyA)) }
            val second = async(kotlinx.coroutines.Dispatchers.IO) { start.await(); secondCommand.execute(request(account, 0L, 12L, null, bodyB)) }
            start.complete(Unit)
            val results = withContext(kotlinx.coroutines.Dispatchers.IO) { withTimeout(30.seconds) { listOf(first.await(), second.await()) } }
            assertEquals(1, results.count { it.isOk }); assertEquals(1, results.count { it.isErr })
            assertEquals(1, policies().findByAccountIdAndKey(account, role).executeAsList().size)
            val current = read().execute(GetAccountMetadataPolicySnapshotArgs(account, identifier, role))
            assertTrue(current.isOk); assertEquals(1L, current.value.revision)
            assertEquals(results.single { it.isOk }.value.policy, current.value.policy)
        } finally { secondDriver.close() }
    }
    @Test fun legacyCreateAndDeleteAdvanceActualRevisionWithoutBypassingFences() = runTest {
        val account = account()
        val created = CreateMetadataPolicyCommandImpl(execution, accountQueries, policies(), revisions())
            .execute(CreateMetadataPolicyArgs(account, role, bodyA))
        assertTrue(created.isOk)
        val first = read().execute(GetAccountMetadataPolicySnapshotArgs(account, identifier, role))
        assertTrue(first.isOk); assertEquals(1L, first.value.revision)
        val replaced = replace().execute(request(account, 1L, 11L, bodyA, bodyB)); assertTrue(replaced.isOk)
        val active = policies().findByAccountIdAndKey(account, role).executeAsOne()
        val deleted = DeleteMetadataPolicyCommandImpl(execution, accountQueries, policies(), revisions())
            .execute(DeleteMetadataPolicyArgs(account, active.id))
        assertTrue(deleted.isOk)
        val last = read().execute(GetAccountMetadataPolicySnapshotArgs(account, identifier, role))
        assertTrue(last.isOk); assertEquals(3L, last.value.revision); assertNull(last.value.policy)
        assertEquals(11L, last.value.operationGeneration)
        val before = rows(); assertTrue(replace().execute(request(account, 3L, 11L, null, bodyA)).isErr)
        assertEquals(before, rows())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private class NoCopyCancellation : CancellationException("Injected policy persistence cancellation"), kotlinx.coroutines.CopyableThrowable<NoCopyCancellation> {
        override fun createCopy(): NoCopyCancellation? = null
    }
    @Test fun cancellationAfterPolicyDeletionRollsBackAndEscapesByIdentity() = runTest {
        val account = account(); assertTrue(replace().execute(request(account, 0L, 11L, null, bodyB)).isOk)
        val cancelled = NoCopyCancellation()
        val wrapped = object : javax.sql.DataSource by source {
            override fun getConnection(): Connection {
                val real = source.connection
                return java.lang.reflect.Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
                    try {
                        val value = method.invoke(real, *(arguments ?: emptyArray()))
                        val sql = arguments?.firstOrNull() as? String
                        if (method.name == "prepareStatement" && sql != null && sql.contains("INSERT INTO AccountMetadataPolicyRevision")) {
                            java.lang.reflect.Proxy.newProxyInstance(java.sql.PreparedStatement::class.java.classLoader,
                                arrayOf(java.sql.PreparedStatement::class.java)) { _, statementMethod, statementArguments ->
                                if (statementMethod.name.startsWith("execute")) throw cancelled
                                try { statementMethod.invoke(value, *(statementArguments ?: emptyArray())) }
                                catch (wrapped: java.lang.reflect.InvocationTargetException) { throw requireNotNull(wrapped.cause) }
                            }
                        } else value
                    } catch (wrapped: java.lang.reflect.InvocationTargetException) { throw requireNotNull(wrapped.cause) }
                } as Connection
            }
        }
        val cancelDriver = wrapped.asJdbcDriver()
        try {
            val command = AtomicReplaceMetadataPolicyCommandImpl(actorExecution(),
                AccountQueries(cancelDriver, Account.Adapter(JavaUuidStringAdapter)),
                com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries(cancelDriver,
                    com.sphereon.openid.fed.persistence.models.MetadataPolicy.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter)),
                com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevisionQueries(cancelDriver,
                    com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevision.Adapter(JavaUuidStringAdapter)))
            val before = rows()
            val thrown = assertFailsWith<CancellationException> { command.execute(request(account, 1L, 13L, bodyB, bodyA)) }
            assertTrue(thrown === cancelled, "The exact cancellation must escape rather than becoming a refusal")
            assertEquals(before, rows(), "Actual deleted/inserted policy rows must roll back with the failed fence")
        } finally { cancelDriver.close() }
    }
    @Test fun missingAndDeletedAccountsCannotMutatePolicyOrFence() = runTest {
        val account = account(); val before = rows()
        assertTrue(replace().execute(request(UUID.randomUUID().toString(), 0L, 11L, null, bodyA)).isErr)
        assertEquals(before, rows())
        accountQueries.delete(account).executeAsOne()
        val deleted = rows(); assertTrue(replace().execute(request(account, 0L, 11L, null, bodyA)).isErr)
        assertEquals(deleted, rows())
    }
    @Test fun historicalRoleSnapshotMatchesStoredGetWithoutChangingAnyPolicyRows() = runTest {
        val account = account(); val historical = policies().create(account, role, bodyB.toString()).executeAsOne()
        policies().delete(historical.id).executeAsOne()
        policies().create(account, role, bodyA.toString()).executeAsOne()
        val subordinateQueries = com.sphereon.openid.fed.persistence.models.SubordinateQueries(driver,
            com.sphereon.openid.fed.persistence.models.Subordinate.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter))
        val child = subordinateQueries.create(account, "https://child.example/legacy").executeAsOne()
        val childKeys = com.sphereon.openid.fed.persistence.models.SubordinateJwkQueries(driver,
            com.sphereon.openid.fed.persistence.models.SubordinateJwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter))
        childKeys.create(child.id, key(account).key).executeAsOne()
        val resolver = object : com.sphereon.openid.fed.core.tenant.TenantContextResolver {
            override suspend fun resolveTenantId(request: com.sphereon.core.api.http.GenericHttpRequest): String? = null
            override suspend fun resolveTenantIdByName(name: String): String? = null
            override suspend fun resolveIdentifier(tenantId: String): String? = if (tenantId == account) identifier else null
        }
        val storedGet = com.sphereon.openid.fed.services.command.subordinate.GetSubordinateStatementCommandImpl(execution, resolver,
            subordinateQueries, childKeys,
            com.sphereon.openid.fed.persistence.models.SubordinateMetadataQueries(driver,
                com.sphereon.openid.fed.persistence.models.SubordinateMetadata.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter, JavaUuidStringAdapter)),
            com.sphereon.openid.fed.persistence.models.SubordinateConstraintQueries(driver,
                com.sphereon.openid.fed.persistence.models.SubordinateConstraint.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter, JavaUuidStringAdapter)),
            policies(), object : com.sphereon.openid.fed.client.config.AppConfigOidfConfigBinder(mockk(relaxed = true)) {
                override fun getFederationConfig() = com.sphereon.openid.fed.core.config.FederationConfig(statementLifetimeSeconds = 300L)
            }, com.sphereon.openid.fed.persistence.models.MetadataQueries(driver,
                com.sphereon.openid.fed.persistence.models.Metadata.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter)))
        val before = rows()
        val statement = storedGet.execute(com.sphereon.openid.fed.services.command.subordinate.GetSubordinateStatementArgs(account, child.id))
        assertTrue(statement.isOk)
        val snapshot = read().execute(GetAccountMetadataPolicySnapshotArgs(account, identifier, role))
        assertTrue(snapshot.isOk)
        assertEquals(statement.value.metadataPolicy?.get(role), snapshot.value.policy)
        assertEquals(0L, snapshot.value.revision)
        assertEquals(1, policies().findByAccountIdAndKey(account, role).executeAsList().size)
        assertEquals(2, before.getValue("MetadataPolicy").size)
        assertEquals(before, rows())
    }

    @Test fun upgradeFromActualSchema16PreservesLiveAndDeletedRowsAndEnforcesActiveUniqueness() = runTest {
        val schema = "policy_upgrade_" + UUID.randomUUID().toString().replace("-", "")
        source.connection.use { connection -> connection.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        val upgraded = PGSimpleDataSource().apply {
            setUrl(schemaJdbcUrl.replace(Regex("currentSchema=[^&]*"), "currentSchema=$schema"))
            user = dbUsername; password = dbPassword
        }
        val upgradeDriver = upgraded.asJdbcDriver()
        try {
            // Migration file16 creates MetadataPolicy; generated schema version17 includes that migration.
            Database.Schema.migrate(upgradeDriver, 0L, 17L)
            val id = UUID.randomUUID()
            upgraded.connection.use { connection ->
                connection.prepareStatement("INSERT INTO Account(id, username, identifier) VALUES (?, ?, ?)").use {
                    it.setObject(1, id); it.setString(2, "upgrade-owner"); it.setString(3, identifier); it.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO MetadataPolicy(account_id, key, policy) VALUES (?, ?, ?)").use {
                    it.setObject(1, id); it.setString(2, role); it.setString(3, bodyA.toString()); it.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO MetadataPolicy(account_id, key, policy, deleted_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)").use {
                    it.setObject(1, id); it.setString(2, "openid_provider"); it.setString(3, bodyB.toString()); it.executeUpdate()
                }
            }
            fun storedRows(): List<String> = upgraded.connection.use { connection -> connection.createStatement().use {
                it.executeQuery("SELECT row_to_json(t)::text FROM MetadataPolicy t ORDER BY id").use { rows ->
                    buildList { while(rows.next()) add(rows.getString(1)) }
                }
            } }
            val before = storedRows()
            Database.Schema.migrate(upgradeDriver, 17L, Database.Schema.version)
            assertEquals(before, storedRows(), "The real schema upgrade must preserve every policy row field")
            upgraded.connection.use { connection ->
                connection.prepareStatement("INSERT INTO MetadataPolicy(account_id, key, policy) VALUES (?, ?, ?)").use {
                    it.setObject(1, id); it.setString(2, "openid_provider"); it.setString(3, bodyA.toString()); it.executeUpdate()
                }
                val duplicate = assertFailsWith<java.sql.SQLException> {
                    connection.prepareStatement("INSERT INTO MetadataPolicy(account_id, key, policy) VALUES (?, ?, ?)").use {
                        it.setObject(1, id); it.setString(2, "openid_provider"); it.setString(3, bodyB.toString()); it.executeUpdate()
                    }
                }
                assertEquals("23505", duplicate.sqlState)
            }
            assertEquals(3, storedRows().size)
            val freshSchema = "policy_fresh_" + UUID.randomUUID().toString().replace("-", "")
            source.connection.use { connection -> connection.createStatement().use { it.execute("CREATE SCHEMA $freshSchema") } }
            val fresh = PGSimpleDataSource().apply {
                setUrl(schemaJdbcUrl.replace(Regex("currentSchema=[^&]*"), "currentSchema=$freshSchema"))
                user = dbUsername; password = dbPassword
            }
            val freshDriver = fresh.asJdbcDriver()
            try {
                Database.Schema.create(freshDriver)
                fun constraintRules(dataSource: javax.sql.DataSource): List<String> = dataSource.connection.use { connection ->
                    connection.createStatement().use { statement -> statement.executeQuery(
                        "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'MetadataPolicy'::regclass ORDER BY conname"
                    ).use { rows -> buildList { while(rows.next()) add(rows.getString(1) + ":" + rows.getString(2)) } } }
                }
                fun uniquePredicates(dataSource: javax.sql.DataSource): List<String> = dataSource.connection.use { connection ->
                    connection.createStatement().use { statement -> statement.executeQuery(
                        "SELECT i.relname, pg_get_expr(x.indpred, x.indrelid) FROM pg_index x JOIN pg_class i ON i.oid=x.indexrelid WHERE x.indrelid='MetadataPolicy'::regclass AND x.indisunique ORDER BY i.relname"
                    ).use { rows -> buildList { while(rows.next()) add(rows.getString(1) + ":" + rows.getString(2)) } } }
                }
                assertEquals(constraintRules(upgraded), constraintRules(fresh), "Fresh Schema.create and legacy upgrade must enforce identical constraints")
                assertEquals(uniquePredicates(upgraded), uniquePredicates(fresh), "Both paths must enforce the same active-only unique predicate")
            } finally { freshDriver.close() }
            val revisions = com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevisionQueries(upgradeDriver,
                com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevision.Adapter(JavaUuidStringAdapter))
            assertTrue(revisions.findByAccountId(id.toString()).executeAsList().isEmpty())
        } finally { upgradeDriver.close() }
    }
}
