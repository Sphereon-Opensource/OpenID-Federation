package com.sphereon.openid.fed.services.command.renewal

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.asCoreApiServiceGraph
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.kms.KeyManagerService
import com.sphereon.crypto.core.kms.asKeyManagerServiceGraph
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderConfig
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderFactoryImpl
import com.sphereon.di.context.PrincipalType
import com.sphereon.di.session.SessionInstance
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.persistence.Database
import com.sphereon.openid.fed.persistence.database.JavaUuidStringAdapter
import com.sphereon.openid.fed.persistence.models.Account
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.AccountSigningKey as AccountSigningKeyEntity
import com.sphereon.openid.fed.persistence.models.AccountSigningKeyQueries
import com.sphereon.openid.fed.persistence.models.EntityConfigurationStatement
import com.sphereon.openid.fed.persistence.models.EntityConfigurationStatementQueries
import com.sphereon.openid.fed.persistence.models.Jwk
import com.sphereon.openid.fed.persistence.models.JwkQueries
import com.sphereon.openid.fed.persistence.models.Subordinate
import com.sphereon.openid.fed.persistence.models.SubordinateQueries
import com.sphereon.openid.fed.persistence.models.SubordinateStatement
import com.sphereon.openid.fed.persistence.models.SubordinateStatementQueries
import com.sphereon.openid.fed.services.command.entityConfiguration.PublishEntityConfigurationArgs
import com.sphereon.openid.fed.services.command.entityConfiguration.PublishEntityConfigurationCommand
import com.sphereon.openid.fed.services.command.resolution.createExactSuperiorEvidenceTestGraph
import com.sphereon.openid.fed.services.command.subordinate.PublishSubordinateStatementArgs
import com.sphereon.openid.fed.services.command.subordinate.PublishSubordinateStatementCommand
import com.sphereon.openid.fed.services.signPayload
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Statement renewal against real PostgreSQL schemas and real software-KMS signatures. The publish commands are the
 * only stand-ins: they sign what the production commands would build, so the renewal's own due selection, key check,
 * compare-and-set and persistence run unmodified.
 */
class RenewDueStatementsCommandTest {
    companion object {
        private var postgres: PostgreSQLContainer<Nothing>? = null

        @JvmStatic @BeforeClass
        fun startPostgres() {
            assertTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
                "Statement renewal tests require Docker and an isolated PostgreSQL container")
            postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                withDatabaseName("oidfed_statement_renewal")
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

    private val issuer = "https://superior.example"
    private val subject = "https://subordinate.example"
    private val now = 1_900_000_000L
    private val lifetime = 3_600L
    private lateinit var driver: SqlDriver
    private lateinit var schema: String
    private lateinit var session: SessionInstance
    private lateinit var execution: SessionExecution
    private lateinit var keyManager: KeyManagerService
    private lateinit var jwtService: JwtService
    private lateinit var accounts: AccountQueries
    private lateinit var jwks: JwkQueries
    private lateinit var selections: AccountSigningKeyQueries
    private lateinit var configurations: EntityConfigurationStatementQueries
    private lateinit var subordinates: SubordinateQueries
    private lateinit var subordinateStatements: SubordinateStatementQueries
    private lateinit var publishEc: PublishEntityConfigurationCommand
    private lateinit var publishSs: PublishSubordinateStatementCommand
    private val invalidated = mutableListOf<String>()
    private lateinit var accountId: String
    private lateinit var subordinateId: String
    private lateinit var signingKey: Jwk

    @Before
    fun setUp(): Unit = runBlocking {
        schema = "renewal_" + UUID.randomUUID().toString().replace("-", "")
        val container = requireNotNull(postgres)
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { c ->
            c.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        val separator = if ('?' in container.jdbcUrl) '&' else '?'
        driver = PGSimpleDataSource().apply {
            setUrl(container.jdbcUrl + separator + "currentSchema=" + schema)
            user = container.username
            password = container.password
        }.asJdbcDriver()
        Database.Schema.migrate(driver, 0L, Database.Schema.version)
        val uuid = JavaUuidStringAdapter
        accounts = AccountQueries(driver, Account.Adapter(uuid))
        jwks = JwkQueries(driver, Jwk.Adapter(uuid, uuid))
        selections = AccountSigningKeyQueries(driver, AccountSigningKeyEntity.Adapter(uuid, uuid))
        configurations = EntityConfigurationStatementQueries(driver, EntityConfigurationStatement.Adapter(uuid, uuid))
        subordinates = SubordinateQueries(driver, Subordinate.Adapter(uuid, uuid))
        subordinateStatements = SubordinateStatementQueries(driver, SubordinateStatement.Adapter(uuid, uuid), Subordinate.Adapter(uuid, uuid))

        val app = createExactSuperiorEvidenceTestGraph(this@RenewDueStatementsCommandTest)
        session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "renewal-" + UUID.randomUUID(), principalType = PrincipalType.ANONYMOUS)
        execution = session.asCoreApiServiceGraph().serviceExecution
        val provider = (app as SoftwareKmsProviderFactoryImpl.Graph).softwareKmsProvider.create(
            SoftwareKmsProviderConfig(id = "renewal-provider-" + UUID.randomUUID()), execution)
        keyManager = session.graph.asKeyManagerServiceGraph().keyManagerService
        keyManager.registerProvider(provider, makeDefaultKms = true)
        jwtService = (session.graph as com.sphereon.crypto.jose.jws.JwtServiceImpl.Graph).jwtService

        accountId = accounts.create("superior-" + UUID.randomUUID(), issuer).executeAsOne().id
        signingKey = key()
        selections.upsert(accountId, signingKey.id, 1L).executeAsOne()
        subordinateId = subordinates.create(accountId, subject).executeAsOne().id

        publishEc = mockk()
        publishSs = mockk()
        coEvery { publishEc.execute(any<PublishEntityConfigurationArgs>()) } coAnswers {
            IdkResult.ok(sign(issuer, issuer, currentKey()))
        }
        coEvery { publishSs.execute(any<PublishSubordinateStatementArgs>()) } coAnswers {
            IdkResult.ok(sign(issuer, subject, currentKey()))
        }
    }

    @After
    fun tearDown() {
        try {
            if (::session.isInitialized) session.destroy()
        } finally {
            try {
                if (::driver.isInitialized) driver.close()
            } finally {
                val container = requireNotNull(postgres)
                DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { c ->
                    c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
                }
            }
        }
    }

    private suspend fun key(): Jwk {
        val generated = keyManager.generateKeyAsync(alg = SignatureAlgorithm.ECDSA_SHA256)
        return jwks.create(accountId, "ES256", requireNotNull(generated.kid), generated.providerId,
            requireNotNull(generated.alias), generated.jose.publicJwk.toJsonString()).executeAsOne()
    }

    private fun currentKey(): Jwk = jwks.findActiveById(requireNotNull(selections.findByAccountId(accountId).executeAsOne().jwk_id)).executeAsOne()

    private suspend fun sign(iss: String, sub: String, key: Jwk, exp: Long = now + lifetime): String {
        val claims = buildJsonObject {
            put("iss", iss)
            put("sub", sub)
            put("iat", now)
            put("exp", exp)
            if (iss != sub) put("source_endpoint", "$iss/fetch")
        }
        val signed = jwtService.signPayload(claims, JwtHeader(typ = "entity-statement+jwt", kid = requireNotNull(key.kid), alg = "ES256"),
            requireNotNull(key.kid), key.kms_key_ref, key.kms)
        assertTrue(signed.isOk, "real signing failed")
        return signed.value
    }

    private fun command() = RenewDueStatementsCommandImpl(
        execution, publishEc, publishSs,
        object : PublishedStatementCacheInvalidator {
            override suspend fun published(statementJwt: String) {
                invalidated += statementJwt
            }
        },
        accounts, configurations, subordinates, subordinateStatements, selections, jwks,
    ) { now }

    private fun storeEc(expiresAt: Long) = configurations.create(accountId, "stored.ec.$expiresAt", expiresAt).executeAsOne()
    private fun storeSs(expiresAt: Long) =
        subordinateStatements.create(subordinateId, issuer, subject, "stored.ss.$expiresAt", expiresAt).executeAsOne()

    private fun claims(jwt: String): JsonObject =
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(jwt.split('.')[1]))).jsonObject

    private fun header(jwt: String): JsonObject =
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(jwt.split('.')[0]))).jsonObject

    @Test
    fun dueStatementsAreRenewedWithTheSelectedKeyBeforeTheyExpire() = runTest {
        storeEc(now + 600)
        storeSs(now + 600)

        val report = command().execute(RenewDueStatementsArgs(renewBeforeSeconds = 1_800)).value

        assertEquals(listOf(StatementRenewalStatus.RENEWED, StatementRenewalStatus.RENEWED), report.outcomes.map { it.status })
        val ec = configurations.findLatestByAccountId(accountId).executeAsOne()
        assertEquals(now + lifetime, ec.expires_at)
        assertEquals(now + lifetime, claims(ec.statement)["exp"]!!.jsonPrimitive.long)
        assertEquals(signingKey.kid, header(ec.statement)["kid"]!!.jsonPrimitive.content)
        val ss = subordinateStatements.findByIssAndSub(issuer, subject).executeAsOne()
        assertEquals(now + lifetime, ss.expires_at)
        assertEquals(subject, claims(ss.statement)["sub"]!!.jsonPrimitive.content)
        assertEquals(listOf(ec.statement, ss.statement), invalidated, "Co-located resolver caches are invalidated")
        coVerify(exactly = 1) { publishEc.execute(PublishEntityConfigurationArgs(tenantId = accountId, dryRun = true)) }
        coVerify(exactly = 1) { publishSs.execute(PublishSubordinateStatementArgs(tenantId = accountId, id = subordinateId, dryRun = true)) }
    }

    @Test
    fun alreadyExpiredStatementsAreRenewedAndStatementsNotYetDueAreLeftAlone() = runTest {
        storeEc(now - 5)
        storeSs(now + 3_000)

        val report = command().execute(RenewDueStatementsArgs(renewBeforeSeconds = 1_800)).value

        assertEquals(listOf(RenewedStatementKind.ENTITY_CONFIGURATION), report.outcomes.map { it.kind })
        assertEquals(now + lifetime, configurations.findLatestByAccountId(accountId).executeAsOne().expires_at)
        assertEquals(now + 3_000, subordinateStatements.findByIssAndSub(issuer, subject).executeAsOne().expires_at)
        coVerify(exactly = 0) { publishSs.execute(any<PublishSubordinateStatementArgs>()) }
    }

    @Test
    fun neverPublishedDeletedAndOtherAccountsAreNotRenewed() = runTest {
        storeSs(now + 10)
        val other = accounts.create("other-" + UUID.randomUUID(), "https://other.example").executeAsOne().id
        configurations.create(other, "stored.other", now + 10).executeAsOne()
        subordinates.delete(subordinateId).executeAsOne()

        val scoped = command().execute(RenewDueStatementsArgs(renewBeforeSeconds = 1_800, accountId = accountId)).value

        assertTrue(scoped.outcomes.isEmpty(), "No EC was ever published for the account and its subordinate is deleted")
        val listed = command().execute(RenewDueStatementsArgs(renewBeforeSeconds = 1_800, listOnly = true)).value
        assertEquals(listOf(other to StatementRenewalStatus.DUE), listed.outcomes.map { it.accountId to it.status })
        coVerify(exactly = 0) { publishEc.execute(any<PublishEntityConfigurationArgs>()) }
    }

    @Test
    fun aRenewalStoredByAnotherInstanceWhileSigningWinsTheCompareAndSet() = runTest {
        storeEc(now + 60)
        coEvery { publishEc.execute(any<PublishEntityConfigurationArgs>()) } coAnswers {
            // Another instance commits its renewal between this instance's due query and its compare-and-set.
            configurations.create(accountId, "stored.by.peer", now + lifetime).executeAsOne()
            IdkResult.ok(sign(issuer, issuer, currentKey()))
        }

        val report = command().execute(RenewDueStatementsArgs(renewBeforeSeconds = 1_800)).value

        assertEquals(StatementRenewalStatus.ALREADY_RENEWED, report.outcomes.single().status)
        assertEquals("stored.by.peer", configurations.findLatestByAccountId(accountId).executeAsOne().statement)
        assertTrue(invalidated.isEmpty())
    }

    @Test
    fun aCandidateSignedWithAKeyThatIsNoLongerSelectedIsDiscarded() = runTest {
        storeEc(now + 60)
        val replacement = key()
        coEvery { publishEc.execute(any<PublishEntityConfigurationArgs>()) } coAnswers {
            val signed = sign(issuer, issuer, currentKey())
            selections.upsert(accountId, replacement.id, 2L).executeAsOne()
            IdkResult.ok(signed)
        }

        val first = command().execute(RenewDueStatementsArgs(renewBeforeSeconds = 1_800)).value
        assertEquals(StatementRenewalStatus.SIGNING_KEY_CHANGED, first.outcomes.single().status)
        assertEquals("stored.ec.${now + 60}", configurations.findLatestByAccountId(accountId).executeAsOne().statement)

        coEvery { publishEc.execute(any<PublishEntityConfigurationArgs>()) } coAnswers {
            IdkResult.ok(sign(issuer, issuer, currentKey()))
        }
        val retried = command().execute(RenewDueStatementsArgs(renewBeforeSeconds = 1_800)).value
        assertEquals(StatementRenewalStatus.RENEWED, retried.outcomes.single().status)
        val stored = configurations.findLatestByAccountId(accountId).executeAsOne().statement
        assertEquals(replacement.kid, header(stored)["kid"]!!.jsonPrimitive.content, "The renewal uses the newly selected key")
    }

    @Test
    fun failuresAndTooShortLifetimesLeaveThePreviousStatementAndDoNotStopOtherRenewals() = runTest {
        storeEc(now + 60)
        storeSs(now + 60)
        coEvery { publishEc.execute(any<PublishEntityConfigurationArgs>()) } returns
            IdkResult.err(ServerError("KMS unavailable"))
        coEvery { publishSs.execute(any<PublishSubordinateStatementArgs>()) } coAnswers {
            IdkResult.ok(sign(issuer, subject, currentKey(), exp = now + 900))
        }

        val report = command().execute(RenewDueStatementsArgs(renewBeforeSeconds = 1_800)).value

        assertEquals(listOf(StatementRenewalStatus.FAILED, StatementRenewalStatus.FAILED), report.outcomes.map { it.status })
        assertEquals(now + 60, configurations.findLatestByAccountId(accountId).executeAsOne().expires_at)
        assertEquals(now + 60, subordinateStatements.findByIssAndSub(issuer, subject).executeAsOne().expires_at)
        assertTrue(command().execute(RenewDueStatementsArgs(renewBeforeSeconds = 0)).isErr)
    }
}
