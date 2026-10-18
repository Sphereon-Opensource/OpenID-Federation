package com.sphereon.openid.fed.services.command.jwk

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
import com.sphereon.openid.fed.core.tenant.TenantServiceConfig
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
 * Dedicated migrated PostgreSQL rows and actual managed software-KMS key records. Only account/key service
 * aggregation is test-local; it reads the same SQLDelight rows that the command must validate.
 */
class AtomicSetAccountSigningKeySelectionCommandTest {
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

    private fun account(entityIdentifier: String? = identifier): String =
        accountQueries.create("prepared-" + UUID.randomUUID(), entityIdentifier).executeAsOne().id

    private fun setAccountIdentifier(accountId: String, entityIdentifier: String?) {
        source.connection.use { connection ->
            connection.prepareStatement("UPDATE Account SET identifier = ? WHERE id = ?").use { statement ->
                statement.setString(1, entityIdentifier)
                statement.setObject(2, UUID.fromString(accountId))
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

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



    private fun command() = AtomicSetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries)
    private fun args(account: String, revision: Long, generation: Long, key: String?, token: String = UUID.randomUUID().toString()) =
        AtomicSetAccountSigningKeySelectionArgs(account, identifier, revision, generation, token, key)
    private fun rows(): Map<String, List<String>> = source.connection.use { connection ->
        listOf("Account", "Jwk", "AccountSigningKey").associateWith { table -> connection.createStatement().use {
            it.executeQuery("SELECT row_to_json(t)::text FROM $table t ORDER BY row_to_json(t)::text").use { rows ->
                buildList { while(rows.next()) add(rows.getString(1)) }
            }
        } }
    }
    @Test fun latestSuccessfulOperationResumesWithoutAnyRowMutation() = runTest {
        val account = account(); val key = key(account); val request = args(account, 0L, 11L, key.id)
        val first = command().execute(request); assertTrue(first.isOk); assertEquals(1L, first.value.revision)
        val before = rows(); val resumed = command().execute(request); assertTrue(resumed.isOk)
        assertEquals(first.value, resumed.value); assertEquals(before, rows())
    }
    @Test fun laterAbaSelectionCannotBeOverwrittenByOldOperationRetry() = runTest {
        val account = account(); val a = key(account); val b = key(account)
        val first = args(account, 0L, 11L, a.id); assertTrue(command().execute(first).isOk)
        assertTrue(command().execute(args(account, 1L, 13L, b.id)).isOk)
        assertTrue(command().execute(args(account, 2L, 15L, a.id)).isOk)
        val before = rows(); assertTrue(command().execute(first).isErr); assertEquals(before, rows())
    }
    @Test fun desiredKeyAtRevisionPlusOneDoesNotProveOperationOwnership() = runTest {
        val account = account(); val key = key(account)
        val legacy = SetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries)
            .execute(SetAccountSigningKeySelectionArgs(account, identifier, 0L, key.id))
        assertTrue(legacy.isOk); val before = rows()
        assertTrue(command().execute(args(account, 0L, 11L, key.id)).isErr)
        assertEquals(before, rows())
    }
    @Test fun sameGenerationDifferentKeyOrTokenCannotReplay() = runTest {
        val account = account(); val a = key(account); val b = key(account)
        val first = args(account, 0L, 11L, a.id); assertTrue(command().execute(first).isOk); val before = rows()
        assertTrue(command().execute(first.copy(selectedKeyId = b.id)).isErr)
        assertTrue(command().execute(first.copy(operationToken = UUID.randomUUID().toString())).isErr)
        assertEquals(before, rows())
    }
    @Test fun legacySameKeyTransitionInvalidatesOldReplayAndRetainsHighestGeneration() = runTest {
        val account = account(); val key = key(account); val first = args(account, 0L, 11L, key.id)
        assertTrue(command().execute(first).isOk)
        val legacy = SetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries)
            .execute(SetAccountSigningKeySelectionArgs(account, identifier, 1L, key.id))
        assertTrue(legacy.isOk); val before = rows()
        assertTrue(command().execute(first).isErr)
        assertTrue(command().execute(args(account, 2L, 10L, key.id)).isErr)
        assertEquals(before, rows())
    }
    @Test fun selectedRevocationClearsBindingAndInvalidatesSuccessfulReplay() = runTest {
        val account = account(); val key = key(account); val first = args(account, 0L, 11L, key.id)
        assertTrue(command().execute(first).isOk)
        val revoked = RevokeKeyCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries)
            .execute(RevokeKeyArgs(account, key.id, reason = null, allowSelected = true))
        assertTrue(revoked.isOk); val before = rows()
        assertTrue(command().execute(first).isErr)
        assertNull(accountSigningKeyQueries.findByAccountId(account).executeAsOne().jwk_id)
        assertEquals(before, rows())
    }
    @Test fun clearHasDurableOperationOwnershipAndExactRetry() = runTest {
        val account = account(); val key = key(account)
        assertTrue(command().execute(args(account, 0L, 11L, key.id)).isOk)
        val clear = args(account, 1L, 13L, null); val cleared = command().execute(clear); assertTrue(cleared.isOk)
        val before = rows(); val repeated = command().execute(clear); assertTrue(repeated.isOk)
        assertNull(repeated.value.selectedKeyId); assertEquals(2L, repeated.value.revision); assertEquals(before, rows())
    }
    @Test fun foreignKeyOrWrongIssuerCannotChangeSelectionOrJournal() = runTest {
        val owner = account(); val foreign = account("https://foreign.example"); val key = key(foreign); val before = rows()
        assertTrue(command().execute(args(owner, 0L, 11L, key.id)).isErr)
        assertTrue(command().execute(args(owner, 0L, 11L, null).copy(expectedEntityIdentifier = "https://foreign.example")).isErr)
        assertEquals(before, rows())
    }

    @Test fun concurrentConnectionsOnlyCommitOneReviewedRevision() = runBlocking {
        val account = account(); val a = key(account); val b = key(account)
        val second = PGSimpleDataSource().apply { setURL(schemaJdbcUrl); user = dbUsername; password = dbPassword }.asJdbcDriver()
        try {
            val other = AtomicSetAccountSigningKeySelectionCommandImpl(execution,
                AccountQueries(second, Account.Adapter(JavaUuidStringAdapter)),
                JwkQueries(second, Jwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter)),
                AccountSigningKeyQueries(second, AccountSigningKey.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter)))
            val start = CompletableDeferred<Unit>()
            val first = async(kotlinx.coroutines.Dispatchers.IO) { start.await(); command().execute(args(account, 0L, 11L, a.id)) }
            val next = async(kotlinx.coroutines.Dispatchers.IO) { start.await(); other.execute(args(account, 0L, 12L, b.id)) }
            start.complete(Unit)
            val results = withTimeout(30.seconds) { listOf(first.await(), next.await()) }
            assertEquals(1, results.count { it.isOk }); assertEquals(1, results.count { it.isErr })
            val stored = accountSigningKeyQueries.findByAccountId(account).executeAsOne()
            assertEquals(1L, stored.revision); assertEquals(results.single { it.isOk }.value.selectedKeyId, stored.jwk_id)
        } finally { second.close() }
    }
    @Test fun terminalRevisionSupportsExactRetryButRevocationInvalidatesIt() = runTest {
        val account = account(); val key = key(account)
        accountSigningKeyQueries.upsert(account, null, Long.MAX_VALUE - 1).executeAsOne()
        val request = args(account, Long.MAX_VALUE - 1, 11L, key.id)
        val selected = command().execute(request); assertTrue(selected.isOk); assertEquals(Long.MAX_VALUE, selected.value.revision)
        val before = rows(); assertTrue(command().execute(request).isOk); assertEquals(before, rows())
        assertTrue(command().execute(args(account, Long.MAX_VALUE, 13L, null)).isErr); assertEquals(before, rows())
        assertTrue(RevokeKeyCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries)
            .execute(RevokeKeyArgs(account, key.id, reason = null, allowSelected = true)).isOk)
        val revoked = rows(); assertTrue(command().execute(request).isErr); assertEquals(revoked, rows())
        val selection = accountSigningKeyQueries.findByAccountId(account).executeAsOne()
        assertEquals(Long.MAX_VALUE, selection.revision); assertNull(selection.jwk_id)
    }
    @Test fun legacyClearInvalidatesReplayAndNeverResetsGeneration() = runTest {
        val account = account(); val key = key(account); val first = args(account, 0L, 11L, key.id)
        assertTrue(command().execute(first).isOk)
        assertTrue(SetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries)
            .execute(SetAccountSigningKeySelectionArgs(account, identifier, 1L, null)).isOk)
        val before = rows(); assertTrue(command().execute(first).isErr)
        assertTrue(command().execute(args(account, 2L, 11L, key.id)).isErr); assertEquals(before, rows())
        assertTrue(command().execute(args(account, 2L, 13L, key.id)).isOk)
    }
    @Test fun missingDeletedAndExpiredOwnershipRefusesWithoutWrites() = runTest {
        val account = account(); val before = rows()
        assertTrue(command().execute(args(UUID.randomUUID().toString(), 0L, 11L, null)).isErr)
        assertTrue(command().execute(args(account, 0L, 11L, UUID.randomUUID().toString())).isErr); assertEquals(before, rows())
        val key = key(account); jwkQueries.retire(key.id).executeAsOne(); val retired = rows()
        assertTrue(command().execute(args(account, 0L, 11L, key.id)).isErr); assertEquals(retired, rows())
        accountQueries.delete(account).executeAsOne(); val deleted = rows()
        assertTrue(command().execute(args(account, 0L, 11L, null)).isErr); assertEquals(deleted, rows())
    }
    @Test fun malformedOperationCoordinatesRefuseWithoutJournalMutation() = runTest {
        val account = account(); val valid = args(account, 0L, 11L, null); val before = rows()
        listOf(valid.copy(operationGeneration = 0L), valid.copy(operationGeneration = -1L),
            valid.copy(operationToken = ""), valid.copy(operationToken = "x".repeat(257)),
            valid.copy(expectedRevision = -1L), valid.copy(accountId = "bad"), valid.copy(selectedKeyId = "bad"))
            .forEach { assertTrue(command().execute(it).isErr) }
        assertEquals(before, rows())
    }

    @Test fun configuredIdentifierAppliesOnlyToSeededRootAcrossReadAndBothWriteCommands() = runTest {
        val root = accountQueries.findByUsername("root").executeAsOne()
        assertNull(root.identifier)
        val rootIdentifier = "https://root.example"
        val config = TenantServiceConfig(rootIdentifier)
        val key = key(root.id)

        val read = FindAccountSigningKeySelectionCommandImpl(execution, accountQueries, accountSigningKeyQueries, config)
            .execute(FindAccountSigningKeySelectionArgs(root.id, rootIdentifier))
        assertTrue(read.isOk)
        assertEquals(rootIdentifier, read.value.entityIdentifier)
        assertEquals(0L, read.value.revision)

        val mismatchedRead = FindAccountSigningKeySelectionCommandImpl(execution, accountQueries, accountSigningKeyQueries, config)
            .execute(FindAccountSigningKeySelectionArgs(root.id, "https://caller.example"))
        assertTrue(mismatchedRead.isErr)
        val beforeLegacyMismatch = rows()
        val mismatchedLegacyWrite = SetAccountSigningKeySelectionCommandImpl(
            execution, accountQueries, jwkQueries, accountSigningKeyQueries, config,
        ).execute(SetAccountSigningKeySelectionArgs(root.id, "https://caller.example", 0L, key.id))
        assertTrue(mismatchedLegacyWrite.isErr)
        val mismatchedAtomicWrite = AtomicSetAccountSigningKeySelectionCommandImpl(
            execution, accountQueries, jwkQueries, accountSigningKeyQueries, config,
        ).execute(AtomicSetAccountSigningKeySelectionArgs(root.id, "https://caller.example", 0L, 1L, "root-op-1", key.id))
        assertTrue(mismatchedAtomicWrite.isErr)
        assertEquals(beforeLegacyMismatch, rows())

        val legacy = SetAccountSigningKeySelectionCommandImpl(
            execution, accountQueries, jwkQueries, accountSigningKeyQueries, config,
        ).execute(SetAccountSigningKeySelectionArgs(root.id, rootIdentifier, 0L, key.id))
        assertTrue(legacy.isOk)
        assertEquals(1L, legacy.value.revision)
        val atomic = AtomicSetAccountSigningKeySelectionCommandImpl(
            execution, accountQueries, jwkQueries, accountSigningKeyQueries, config,
        ).execute(AtomicSetAccountSigningKeySelectionArgs(root.id, rootIdentifier, 1L, 1L, "root-op-2", key.id))
        assertTrue(atomic.isOk)
        assertEquals(rootIdentifier, atomic.value.entityIdentifier)
        assertEquals(key.id, atomic.value.selectedKeyId)
        assertEquals(2L, atomic.value.revision)
    }

    @Test fun nonRootNullIdentifierAndPersistedIdentifierMismatchRemainRejected() = runTest {
        val rootIdentifier = "https://root.example"
        val config = TenantServiceConfig(rootIdentifier)
        val nullIdentifierId = account(entityIdentifier = null)
        val nullIdentifier = accountQueries.findById(nullIdentifierId).executeAsOne()
        val callerSuppliedFallback = "$rootIdentifier/${nullIdentifier.username}"
        val nullBefore = rows()

        assertTrue(FindAccountSigningKeySelectionCommandImpl(execution, accountQueries, accountSigningKeyQueries, config)
            .execute(FindAccountSigningKeySelectionArgs(nullIdentifierId, callerSuppliedFallback)).isErr)
        assertTrue(SetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries, config)
            .execute(SetAccountSigningKeySelectionArgs(nullIdentifierId, callerSuppliedFallback, 0L, null)).isErr)
        assertTrue(AtomicSetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries, config)
            .execute(AtomicSetAccountSigningKeySelectionArgs(nullIdentifierId, callerSuppliedFallback, 0L, 1L, "nonroot-op", null)).isErr)
        assertEquals(nullBefore, rows())

        val persistedIdentifier = "https://persisted.example/entity"
        val explicitId = account(entityIdentifier = persistedIdentifier)
        assertTrue(FindAccountSigningKeySelectionCommandImpl(execution, accountQueries, accountSigningKeyQueries, config)
            .execute(FindAccountSigningKeySelectionArgs(explicitId, rootIdentifier)).isErr)
        val beforeMismatch = rows()
        assertTrue(SetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries, config)
            .execute(SetAccountSigningKeySelectionArgs(explicitId, rootIdentifier, 0L, null)).isErr)
        assertTrue(AtomicSetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries, config)
            .execute(AtomicSetAccountSigningKeySelectionArgs(explicitId, rootIdentifier, 0L, 1L, "mismatch-op", null)).isErr)
        assertEquals(beforeMismatch, rows())
    }
    @Test fun rootPersistedIdentifierIsAuthoritativeAndBlankConfigurationCannotFillNull() = runTest {
        val root = accountQueries.findByUsername("root").executeAsOne()
        assertNull(root.identifier)
        val blankConfig = TenantServiceConfig("")
        val beforeBlank = rows()
        assertTrue(FindAccountSigningKeySelectionCommandImpl(execution, accountQueries, accountSigningKeyQueries, blankConfig)
            .execute(FindAccountSigningKeySelectionArgs(root.id, "https://root.example")).isErr)
        assertTrue(SetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries, blankConfig)
            .execute(SetAccountSigningKeySelectionArgs(root.id, "https://root.example", 0L, null)).isErr)
        assertTrue(AtomicSetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries, blankConfig)
            .execute(AtomicSetAccountSigningKeySelectionArgs(root.id, "https://root.example", 0L, 1L, "blank-root-op", null)).isErr)
        assertEquals(beforeBlank, rows())

        val persistedIdentifier = "https://persisted-root.example"
        val staleConfiguredIdentifier = TenantServiceConfig("https://configured-root.example")
        setAccountIdentifier(root.id, persistedIdentifier)
        val key = key(root.id)
        val find = FindAccountSigningKeySelectionCommandImpl(execution, accountQueries, accountSigningKeyQueries, staleConfiguredIdentifier)
        assertTrue(find.execute(FindAccountSigningKeySelectionArgs(root.id, persistedIdentifier)).isOk)
        assertTrue(find.execute(FindAccountSigningKeySelectionArgs(root.id, staleConfiguredIdentifier.rootIdentifier)).isErr)

        val beforeMismatch = rows()
        assertTrue(SetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries, staleConfiguredIdentifier)
            .execute(SetAccountSigningKeySelectionArgs(root.id, staleConfiguredIdentifier.rootIdentifier, 0L, key.id)).isErr)
        assertTrue(AtomicSetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries, staleConfiguredIdentifier)
            .execute(AtomicSetAccountSigningKeySelectionArgs(root.id, staleConfiguredIdentifier.rootIdentifier, 0L, 1L, "stale-root-op", key.id)).isErr)
        assertEquals(beforeMismatch, rows())

        val legacy = SetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries, staleConfiguredIdentifier)
            .execute(SetAccountSigningKeySelectionArgs(root.id, persistedIdentifier, 0L, key.id))
        assertTrue(legacy.isOk)
        assertEquals(1L, legacy.value.revision)
    }

    @Test fun rootFallbackRetainsKeyOwnershipRevocationCasAndAtomicRetryChecks() = runTest {
        val root = accountQueries.findByUsername("root").executeAsOne()
        val rootIdentifier = "https://root.example"
        val config = TenantServiceConfig(rootIdentifier)
        val rootKey = key(root.id)
        val foreignKey = key(account("https://foreign.example"))
        val command = AtomicSetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries, config)

        val beforeForeign = rows()
        assertTrue(command.execute(AtomicSetAccountSigningKeySelectionArgs(root.id, rootIdentifier, 0L, 1L, "root-foreign", foreignKey.id)).isErr)
        assertEquals(beforeForeign, rows())

        val request = AtomicSetAccountSigningKeySelectionArgs(root.id, rootIdentifier, 0L, 3L, "root-retry", rootKey.id)
        val selected = command.execute(request)
        assertTrue(selected.isOk)
        val afterSelection = rows()
        val retry = command.execute(request)
        assertTrue(retry.isOk)
        assertEquals(selected.value, retry.value)
        assertEquals(afterSelection, rows())

        val beforeStale = rows()
        assertTrue(command.execute(request.copy(expectedRevision = 0L, operationGeneration = 5L, operationToken = "root-stale")).isErr)
        assertEquals(beforeStale, rows())

        val revoked = RevokeKeyCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries)
            .execute(RevokeKeyArgs(root.id, rootKey.id, reason = null, allowSelected = true))
        assertTrue(revoked.isOk)
        val afterRevocation = rows()
        val revisionAfterRevocation = accountSigningKeyQueries.findByAccountId(root.id).executeAsOne().revision
        assertTrue(command.execute(AtomicSetAccountSigningKeySelectionArgs(
            root.id, rootIdentifier, revisionAfterRevocation, 7L, "root-revoked", rootKey.id,
        )).isErr)
        assertEquals(afterRevocation, rows())
    }
    private var executedSelectionInserts = 0
    private fun failingWriteDriver(failure: Throwable): SqlDriver = (object : javax.sql.DataSource by source {
        override fun getConnection(): Connection {
            val real = source.connection
            return java.lang.reflect.Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
                try {
                    val value = method.invoke(real, *(arguments ?: emptyArray()))
                    val sql = arguments?.firstOrNull() as? String
                    if (method.name == "prepareStatement" && sql != null && sql.contains("INSERT INTO AccountSigningKey")) {
                        java.lang.reflect.Proxy.newProxyInstance(java.sql.PreparedStatement::class.java.classLoader,
                            arrayOf(java.sql.PreparedStatement::class.java)) { _, statementMethod, statementArguments ->
                            try {
                                val result = statementMethod.invoke(value, *(statementArguments ?: emptyArray()))
                                if (statementMethod.name.startsWith("execute")) { executedSelectionInserts++; throw failure }
                                result
                            } catch (wrapped: java.lang.reflect.InvocationTargetException) { throw requireNotNull(wrapped.cause) }
                        }
                    } else value
                } catch (wrapped: java.lang.reflect.InvocationTargetException) { throw requireNotNull(wrapped.cause) }
            } as Connection
        }
    }).asJdbcDriver()
    private fun command(driver: SqlDriver) = AtomicSetAccountSigningKeySelectionCommandImpl(execution,
        AccountQueries(driver, Account.Adapter(JavaUuidStringAdapter)),
        JwkQueries(driver, Jwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter)),
        AccountSigningKeyQueries(driver, AccountSigningKey.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter)))
    @Test fun failureAfterActualSelectionWriteRollsBackSelectionAndJournal() = runTest {
        val account = account(); val key = key(account); val before = rows()
        val failed = failingWriteDriver(IllegalStateException("After actual insert"))
        try { assertTrue(command(failed).execute(args(account, 0L, 11L, key.id)).isErr); assertEquals(1, executedSelectionInserts); assertEquals(before, rows()) }
        finally { failed.close() }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private class NoCopyCancellation : CancellationException("After actual signing selection write"), kotlinx.coroutines.CopyableThrowable<NoCopyCancellation> {
        override fun createCopy(): NoCopyCancellation? = null
    }
    @Test fun cancellationAfterActualWriteRollsBackAndEscapesByIdentity() = runTest {
        val account = account(); val key = key(account); val before = rows(); val cancellation = NoCopyCancellation()
        val failed = failingWriteDriver(cancellation)
        try {
            val thrown = assertFailsWith<CancellationException> { command(failed).execute(args(account, 0L, 11L, key.id)) }
            assertTrue(thrown === cancellation); assertEquals(1, executedSelectionInserts); assertEquals(before, rows())
        } finally { failed.close() }
    }

    @Test fun schema25UpgradePreservesLegacySelectionAndMatchesFreshConstraints() = runTest {
        fun isolatedSource(prefix: String): PGSimpleDataSource {
            val schema = prefix + UUID.randomUUID().toString().replace("-", "")
            source.connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
            return PGSimpleDataSource().apply {
                setURL(schemaJdbcUrl.substringBefore("currentSchema=") + "currentSchema=" + schema)
                user = dbUsername; password = dbPassword
            }
        }
        val old = isolatedSource("selection_upgrade_"); val oldDriver = old.asJdbcDriver()
        val fresh = isolatedSource("selection_fresh_"); val freshDriver = fresh.asJdbcDriver()
        try {
            assertEquals(28L, Database.Schema.version, "Administration migration27 follows the retained Trust Mark definition migration26")
            Database.Schema.migrate(oldDriver, 0L, 25L)
            val id = UUID.randomUUID()
            old.connection.use { connection ->
                connection.prepareStatement("INSERT INTO Account(id, username, identifier) VALUES(?, ?, ?)").use {
                    it.setObject(1,id); it.setString(2,"upgrade-owner"); it.setString(3,identifier); it.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO AccountSigningKey(account_id, revision) VALUES(?, 7)").use {
                    it.setObject(1,id); it.executeUpdate()
                }
            }
            fun legacyRows(): List<String> = old.connection.use { connection -> connection.createStatement().use {
                it.executeQuery("SELECT account_id::text, jwk_id::text, revision, updated_at::text FROM AccountSigningKey ORDER BY account_id").use { r ->
                    buildList { while(r.next()) add((1..4).joinToString("|") { r.getString(it) ?: "null" }) }
                }
            } }
            val before=legacyRows(); Database.Schema.migrate(oldDriver,25L,Database.Schema.version)
            assertEquals(before,legacyRows())
            Database.Schema.create(freshDriver)
            fun columns(ds: PGSimpleDataSource): List<String> = ds.connection.use { connection -> connection.createStatement().use {
                it.executeQuery("SELECT column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='accountsigningkey' ORDER BY ordinal_position").use { r -> buildList { while(r.next()) add(r.getString(1)) } }
            } }
            fun constraints(ds: PGSimpleDataSource): List<String> = ds.connection.use { connection -> connection.createStatement().use {
                it.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='accountsigningkey'::regclass ORDER BY pg_get_constraintdef(oid)").use { r -> buildList { while(r.next()) add(r.getString(1)) } }
            } }
            assertEquals(columns(old),columns(fresh)); assertEquals(constraints(old),constraints(fresh))
            val q=AccountSigningKeyQueries(oldDriver, AccountSigningKey.Adapter(JavaUuidStringAdapter,JavaUuidStringAdapter))
            val row=q.findByAccountId(id.toString()).executeAsOne()
            assertEquals(7L,row.revision); assertNull(row.last_operation_generation); assertNull(row.last_operation_token); assertNull(row.last_request_snapshot)
            old.connection.use { connection ->
                val invalid=assertFailsWith<SQLException> { connection.createStatement().use { it.execute("UPDATE AccountSigningKey SET last_operation_token='incomplete' WHERE account_id='$id'") } }
                assertEquals("23514",invalid.sqlState)
            }
        } finally { oldDriver.close(); freshDriver.close() }
    }
}

