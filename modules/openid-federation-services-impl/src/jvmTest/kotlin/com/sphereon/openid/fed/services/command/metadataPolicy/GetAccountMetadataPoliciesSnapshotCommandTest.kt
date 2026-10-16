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
class GetAccountMetadataPoliciesSnapshotCommandTest {
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

    private fun full() = GetAccountMetadataPoliciesSnapshotCommandImpl(execution, accountQueries, policies(), revisions())
    private fun fullArgs(account: String) = GetAccountMetadataPoliciesSnapshotArgs(account, identifier)
    @Test fun legacyActiveRolesHaveZeroRevisionAndNoSnapshotWrites() = runTest {
        val account=account(); policies().create(account,role,bodyA.toString()).executeAsOne()
        val before=rows(); val result=full().execute(fullArgs(account)); assertTrue(result.isOk)
        assertEquals(account,result.value.accountId); assertEquals(identifier,result.value.entityIdentifier)
        assertEquals(JsonObject(mapOf(role to bodyA)),result.value.metadataPolicy)
        assertEquals(mapOf(role to 0L),result.value.metadataPolicyRevisions)
        assertEquals(mapOf(role to null),result.value.operationGenerations); assertEquals(before,rows())
    }
    @Test fun deletedCustomRoleFenceSurvivesInFullSnapshot() = runTest {
        val account=account(); val custom="https://custom.example/metadata-role"
        assertTrue(replace().execute(request(account,0L,11L,null,bodyA).copy(entityType=custom)).isOk)
        assertTrue(replace().execute(request(account,1L,13L,bodyA,null).copy(entityType=custom)).isOk)
        assertTrue(replace().execute(request(account,0L,15L,null,bodyB)).isOk)
        val before=rows(); val result=full().execute(fullArgs(account)); assertTrue(result.isOk)
        assertEquals(JsonObject(mapOf(role to bodyB)),result.value.metadataPolicy)
        assertEquals(mapOf(custom to 2L,role to 1L),result.value.metadataPolicyRevisions)
        assertEquals(mapOf(custom to 13L,role to 15L),result.value.operationGenerations); assertEquals(before,rows())
    }
    @Test fun identicalBodyAfterAbaRetainsDifferentFullRevisionWitness() = runTest {
        val account=account(); assertTrue(replace().execute(request(account,0L,11L,null,bodyA)).isOk)
        val first=full().execute(fullArgs(account)); assertTrue(first.isOk)
        assertTrue(replace().execute(request(account,1L,13L,bodyA,bodyB)).isOk)
        assertTrue(replace().execute(request(account,2L,15L,bodyB,bodyA)).isOk)
        val last=full().execute(fullArgs(account)); assertTrue(last.isOk)
        assertEquals(first.value.metadataPolicy,last.value.metadataPolicy)
        assertEquals(mapOf(role to 1L),first.value.metadataPolicyRevisions)
        assertEquals(mapOf(role to 3L),last.value.metadataPolicyRevisions)
    }
    @Test fun emptyAccountProducesNonnullableEmptyWitnessWithoutWrites() = runTest {
        val account=account(); val before=rows(); val result=full().execute(fullArgs(account)); assertTrue(result.isOk)
        assertEquals(JsonObject(emptyMap()),result.value.metadataPolicy)
        assertEquals(emptyMap(),result.value.metadataPolicyRevisions); assertEquals(emptyMap(),result.value.operationGenerations)
        assertEquals(before,rows())
    }
    @Test fun wrongIssuerMissingOrDeletedAccountRefusesWithoutWrites() = runTest {
        val account=account(); val before=rows()
        assertTrue(full().execute(fullArgs(account).copy(expectedEntityIdentifier="https://foreign.example")).isErr)
        assertTrue(full().execute(fullArgs(UUID.randomUUID().toString())).isErr); assertEquals(before,rows())
        accountQueries.delete(account).executeAsOne(); val deleted=rows()
        assertTrue(full().execute(fullArgs(account)).isErr); assertEquals(deleted,rows())
    }

    @Test fun bodyAndRevisionQueriesRunInOneActualAccountLockedTransaction() = runTest {
        val account=account(); assertTrue(replace().execute(request(account,0L,11L,null,bodyA)).isOk)
        var lockObserved=false; var policyRead=false; var revisionRead=false
        val wrapped=object : javax.sql.DataSource by source {
            override fun getConnection(): Connection {
                val real=source.connection
                return java.lang.reflect.Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) { _, method, arguments ->
                    try {
                        val sql=arguments?.firstOrNull() as? String
                        val statement=method.invoke(real,*(arguments ?: emptyArray()))
                        if(method.name=="prepareStatement" && sql!=null) {
                            java.lang.reflect.Proxy.newProxyInstance(java.sql.PreparedStatement::class.java.classLoader,arrayOf(java.sql.PreparedStatement::class.java)) { _, statementMethod, statementArguments ->
                                try {
                                    if(statementMethod.name.startsWith("execute")) {
                                        if(sql.contains("FOR UPDATE")) { assertFalse(real.autoCommit); lockObserved=true }
                                        if(sql.contains("FROM MetadataPolicy")) { assertTrue(lockObserved); assertFalse(real.autoCommit); policyRead=true }
                                        if(sql.contains("FROM AccountMetadataPolicyRevision")) { assertTrue(lockObserved); assertFalse(real.autoCommit); revisionRead=true }
                                    }
                                    statementMethod.invoke(statement,*(statementArguments ?: emptyArray()))
                                } catch(wrapped: java.lang.reflect.InvocationTargetException) { throw requireNotNull(wrapped.cause) }
                            }
                        } else statement
                    } catch(wrapped: java.lang.reflect.InvocationTargetException) { throw requireNotNull(wrapped.cause) }
                } as Connection
            }
        }
        val observed=wrapped.asJdbcDriver()
        try {
            val command=GetAccountMetadataPoliciesSnapshotCommandImpl(execution,
                AccountQueries(observed,Account.Adapter(JavaUuidStringAdapter)),
                com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries(observed,com.sphereon.openid.fed.persistence.models.MetadataPolicy.Adapter(JavaUuidStringAdapter,JavaUuidStringAdapter)),
                com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevisionQueries(observed,com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevision.Adapter(JavaUuidStringAdapter)))
            val before=rows(); val result=command.execute(fullArgs(account)); assertTrue(result.isOk)
            assertTrue(lockObserved && policyRead && revisionRead); assertEquals(before,rows())
        } finally { observed.close() }
    }
}
