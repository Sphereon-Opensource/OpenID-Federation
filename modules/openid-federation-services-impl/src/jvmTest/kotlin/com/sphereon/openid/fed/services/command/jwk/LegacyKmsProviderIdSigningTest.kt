package com.sphereon.openid.fed.services.command.jwk

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
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
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.persistence.Database
import com.sphereon.openid.fed.services.command.resolution.createExactSuperiorEvidenceTestGraph
import com.sphereon.openid.fed.services.signPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Releases before the IDK based ones stored the KmsType enum name (MEMORY, AWS, AZURE) in `Jwk.kms`, while the KMS
 * provider registry matches provider ids exactly and registers them in lower case. A key row written by such a release
 * must therefore stop signing until migration 29 lower cases its provider id, and sign again once it has.
 */
class LegacyKmsProviderIdSigningTest {
    companion object {
        private var postgres: PostgreSQLContainer<Nothing>? = null

        @JvmStatic @BeforeClass
        fun startPostgres() {
            assertTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
                "The legacy KMS provider id test requires Docker and an isolated PostgreSQL container")
            postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                withDatabaseName("oidfed_legacy_kms")
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

    private lateinit var schema: String
    private lateinit var source: PGSimpleDataSource
    private lateinit var session: SessionInstance
    private lateinit var execution: SessionExecution
    private lateinit var keyManager: KeyManagerService
    private lateinit var jwtService: JwtService

    @Before
    fun setUp(): Unit = runBlocking {
        val container = requireNotNull(postgres)
        schema = "legacy_kms_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        val separator = if ('?' in container.jdbcUrl) '&' else '?'
        source = PGSimpleDataSource().apply {
            setUrl(container.jdbcUrl + separator + "currentSchema=" + schema)
            user = container.username
            password = container.password
        }
        val app = createExactSuperiorEvidenceTestGraph(this@LegacyKmsProviderIdSigningTest)
        session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "legacy-kms-" + UUID.randomUUID(), principalType = PrincipalType.ANONYMOUS)
        execution = session.asCoreApiServiceGraph().serviceExecution
        val provider = (app as SoftwareKmsProviderFactoryImpl.Graph).softwareKmsProvider.create(
            SoftwareKmsProviderConfig(id = "memory"), execution)
        keyManager = session.graph.asKeyManagerServiceGraph().keyManagerService
        keyManager.registerProvider(provider, makeDefaultKms = true)
        jwtService = (session.graph as com.sphereon.crypto.jose.jws.JwtServiceImpl.Graph).jwtService
    }

    @After
    fun tearDown() {
        try {
            if (::session.isInitialized) session.destroy()
        } finally {
            val container = requireNotNull(postgres)
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
            }
        }
    }

    private suspend fun signWith(kid: String, kmsKeyRef: String, kmsProviderId: String) = runCatching {
        val claims = buildJsonObject { put("iss", "https://legacy.example"); put("sub", "https://legacy.example"); put("iat", 1_900_000_000L); put("exp", 1_900_003_600L) }
        jwtService.signPayload(claims, JwtHeader(typ = "entity-statement+jwt", kid = kid, alg = "ES256"), kid, kmsKeyRef, kmsProviderId)
    }

    @Test
    fun shouldSignWithAKeyRowOfAnOfficialReleaseOnlyAfterMigration29LowerCasesItsProviderId() = runBlocking {
        val driver = source.asJdbcDriver()
        try {
            // Schema version 17 is what the last official releases leave behind (migrations 1 to 16 applied).
            Database.Schema.migrate(driver, 0L, 17L)
            val generated = keyManager.generateKeyAsync(alg = SignatureAlgorithm.ECDSA_SHA256)
            val kid = requireNotNull(generated.kid)
            val alias = requireNotNull(generated.alias)
            val accountId = source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "INSERT INTO Account (username, identifier) VALUES ('legacy-account', 'https://legacy.example') RETURNING id::text",
                    ).use { rows -> assertTrue(rows.next()); rows.getString(1) }
                }
            }
            val keyId = UUID.randomUUID().toString()
            source.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO Jwk (id, account_id, alg, kid, kms, kms_key_ref, key) VALUES (?::uuid, ?::uuid, NULL, ?, 'MEMORY', ?, ?)",
                ).use { statement ->
                    statement.setString(1, keyId); statement.setString(2, accountId); statement.setString(3, kid)
                    statement.setString(4, alias); statement.setString(5, generated.jose.publicJwk.toJsonString())
                    statement.executeUpdate()
                }
            }

            val before = signWith(kid, alias, "MEMORY")
            assertTrue(before.isFailure || before.getOrThrow().isErr, "the registry matches provider ids exactly, so MEMORY must not resolve: $before")
            val refusal = before.exceptionOrNull()?.message ?: before.getOrThrow().toString()
            assertTrue("MEMORY" in refusal, "the refusal must be about the provider id and not another fault: $refusal")

            Database.Schema.migrate(driver, 17L, Database.Schema.version)

            val storedProviderId = source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT kms FROM Jwk WHERE id = '$keyId'::uuid").use { rows -> assertTrue(rows.next()); rows.getString(1) }
                }
            }
            assertEquals("memory", storedProviderId)
            val after = signWith(kid, alias, storedProviderId)
            assertTrue(after.isSuccess && after.getOrThrow().isOk, "the migrated row must sign with the key it already references: $after")
        } finally {
            driver.close()
        }
    }
}
