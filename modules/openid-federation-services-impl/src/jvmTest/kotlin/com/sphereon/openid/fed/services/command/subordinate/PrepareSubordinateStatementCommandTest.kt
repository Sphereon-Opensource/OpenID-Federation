package com.sphereon.openid.fed.services.command.subordinate

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
class PrepareSubordinateStatementCommandTest {
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


    private fun findSelectionCommand() = FindAccountSigningKeySelectionCommandImpl(execution, accountQueries, accountSigningKeyQueries)
    private fun resolveCommand() = com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyCommandImpl(execution, findSelectionCommand(), jwkQueries)
    private suspend fun select(accountId: String, algorithm: SignatureAlgorithm = SignatureAlgorithm.ECDSA_SHA256, joseAlgorithm: String = "ES256"): Jwk {
        val row = key(accountId, algorithm, joseAlgorithm)
        val result = SetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries)
            .execute(SetAccountSigningKeySelectionArgs(accountId, identifier, 0L, row.id))
        assertTrue(result.isOk)
        return row
    }
    private fun policies() = com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries(driver,
        com.sphereon.openid.fed.persistence.models.MetadataPolicy.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter))
    private fun metadata() = com.sphereon.openid.fed.persistence.models.MetadataQueries(driver,
        com.sphereon.openid.fed.persistence.models.Metadata.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter))
    private fun command(lifetime: Long? = 300L) = PrepareSubordinateStatementCommandImpl(execution, accountRepository,
        recordingJwt, resolveCommand(), object : com.sphereon.openid.fed.client.config.AppConfigOidfConfigBinder(mockk(relaxed = true)) {
            override fun getFederationConfig() = com.sphereon.openid.fed.core.config.FederationConfig(statementLifetimeSeconds = lifetime)
        }, policies(), metadata(), jwkQueries, com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevisionQueries(driver,
            com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevision.Adapter(JavaUuidStringAdapter)), accountQueries)
    private fun snapshot(): Map<String, List<String>> = source.connection.use { connection ->
        listOf("Account", "Jwk", "AccountSigningKey", "AccountMetadataPolicyRevision", "Subordinate", "SubordinateJwk", "SubordinateMetadata",
            "SubordinateConstraint", "MetadataPolicy", "Metadata", "SubordinateStatement", "EntityConfigurationStatement")
            .associateWith { table -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT row_to_json(t)::text FROM $table AS t ORDER BY row_to_json(t)::text").use { result ->
                    buildList { while (result.next()) add(result.getString(1)) }
                }
            } }
    }
    private fun args(accountId: String, row: Jwk) = PrepareSubordinateStatementArgs(accountId,
        "https://child.example/case/%2F", listOf(Json.parseToJsonElement(row.key).jsonObject),
        Json.parseToJsonElement("""{"openid_relying_party":{"redirect_uris":["https://child.example/cb"],"x_unknown":{"typed":true}}}""").jsonObject)

    @Test fun explicitSnapshotProducesVerifiedManagedSignatureWithoutAnyStoreMutation() = runTest {
        val account = account(); val row = select(account); val before = snapshot()
        val result = command().execute(args(account, row))
        assertTrue(result.isOk, "pure preparation must produce a compact JWT")
        val compact = result.value.compact
        val verified = jwtService.verifyJws(VerifyJwsArgs(jws = JwsCompact(compact), trustedJwks = buildJsonObject { put("keys", JsonArray(listOf(Json.parseToJsonElement(row.key).jsonObject))) }))
        assertTrue(verified.isOk && verified.value.isValid, "independent managed-provider signature verification")
        val payload = Json.parseToJsonElement(String(java.util.Base64.getUrlDecoder().decode(compact.split('.')[1]))).jsonObject
        assertEquals(identifier, payload["iss"]?.jsonPrimitive?.content)
        assertEquals(args(account, row).subject, payload["sub"]?.jsonPrimitive?.content)
        assertEquals(args(account, row).metadata, payload["metadata"])
        assertEquals(300L, java.math.BigDecimal(payload.getValue("exp").jsonPrimitive.content).longValueExact() - java.math.BigDecimal(payload.getValue("iat").jsonPrimitive.content).longValueExact())
        assertFalse(payload.containsKey("trust_marks"))
        assertEquals(account, result.value.accountId)
        assertEquals(row.id, result.value.selectedKeyId)
        assertEquals(row.kid, result.value.kid)
        assertEquals("ES256", result.value.algorithm)
        assertEquals(1L, result.value.selectionRevision)
        assertEquals(JsonObject(emptyMap()), result.value.metadataPolicy)
        assertEquals(before, snapshot(), "all complete persisted rows must remain identical")
    }
    @Test fun accountPolicyAndPublishedFetchEndpointAreReadWithoutMutation() = runTest {
        val account = account(); val row = select(account)
        policies().create(account, "openid_relying_party", """{"organization_name":{"default":"Reviewed"}}""").executeAsOne()
        metadata().create(account, "federation_entity", """{"federation_fetch_endpoint":"https://entity.example/custom/fetch"}""").executeAsOne()
        val before = snapshot(); val result = command().execute(args(account, row)); assertTrue(result.isOk)
        val payload = Json.parseToJsonElement(String(java.util.Base64.getUrlDecoder().decode(result.value.compact.split('.')[1]))).jsonObject
        assertEquals("https://entity.example/custom/fetch", payload["source_endpoint"]?.jsonPrimitive?.content)
        assertEquals(Json.parseToJsonElement("""{"openid_relying_party":{"organization_name":{"default":"Reviewed"}}}"""), payload["metadata_policy"])
        assertEquals(before, snapshot())
    }
    @Test fun missingAccountCannotSignOrMutate() = runTest {
        val account = account(); val row = select(account); val before = snapshot()
        assertTrue(command().execute(args(UUID.randomUUID().toString(), row)).isErr)
        assertEquals(0, recordingJwt.signCalls); assertEquals(before, snapshot())
    }
    @Test fun absentSelectionCannotFallbackToAnActiveKey() = runTest {
        val account = account(); val row = key(account); val before = snapshot()
        assertTrue(command().execute(args(account, row)).isErr)
        assertEquals(0, recordingJwt.signCalls); assertEquals(before, snapshot())
    }
    @Test fun reviewedPrivateKeyMaterialCannotReachSigner() = runTest {
        val account = account(); val row = select(account); val before = snapshot()
        val request = args(account, row).copy(publicJwks = listOf(JsonObject(Json.parseToJsonElement(row.key).jsonObject + ("d" to JsonPrimitive("private")))))
        assertTrue(command().execute(request).isErr); assertEquals(0, recordingJwt.signCalls); assertEquals(before, snapshot())
    }
    @Test fun malformedAccountPolicyCannotReachSigner() = runTest {
        val account = account(); val row = select(account)
        policies().create(account, "openid_relying_party", """{"organization_name":{"value":1,"one_of":"invalid"}}""").executeAsOne()
        val before = snapshot(); assertTrue(command().execute(args(account, row)).isErr)
        assertEquals(0, recordingJwt.signCalls); assertEquals(before, snapshot())
    }
    @Test fun missingLifetimeCannotReachSigner() = runTest {
        val account = account(); val row = select(account); val before = snapshot()
        assertTrue(command(null).execute(args(account, row)).isErr)
        assertEquals(0, recordingJwt.signCalls); assertEquals(before, snapshot())
    }

    @Test fun es384AndEs512UseTheirActualSelectedManagedKeys() = runTest {
        for ((algorithm, jose) in listOf(SignatureAlgorithm.ECDSA_SHA384 to "ES384", SignatureAlgorithm.ECDSA_SHA512 to "ES512")) {
            val account = account(); val row = select(account, algorithm, jose); val before = snapshot()
            val result = command().execute(args(account, row)); assertTrue(result.isOk)
            val verified = jwtService.verifyJws(VerifyJwsArgs(jws = JwsCompact(result.value.compact),
                trustedJwks = buildJsonObject { put("keys", JsonArray(listOf(Json.parseToJsonElement(row.key).jsonObject))) }))
            assertTrue(verified.isOk && verified.value.isValid)
            assertEquals(jose, result.value.algorithm); assertEquals(before, snapshot())
        }
    }
    @Test fun expectedSelectionRevisionIsConditionalBeforeSigning() = runTest {
        val account = account(); val row = select(account); val before = snapshot()
        assertTrue(command().execute(args(account, row).copy(expectedSelectionRevision = 0L)).isErr)
        assertEquals(0, recordingJwt.signCalls); assertEquals(before, snapshot())
    }
    @Test fun expectedPolicyIsComparedWithAuthoritativeAccountPolicyBeforeSigning() = runTest {
        val account = account(); val row = select(account); val before = snapshot()
        assertTrue(command().execute(args(account, row).copy(expectedMetadataPolicy =
            Json.parseToJsonElement("""{"openid_relying_party":{"organization_name":{"default":"Other"}}}""").jsonObject)).isErr)
        assertEquals(0, recordingJwt.signCalls); assertEquals(before, snapshot())
    }
    @Test fun policyChangedDuringManagedSigningCannotReturnPreparedStatement() = runTest {
        val account = account(); val row = select(account)
        recordingJwt.afterSigning = {
            policies().create(account, "openid_relying_party", """{"organization_name":{"default":"Changed"}}""").executeAsOne()
        }
        val result = command().execute(args(account, row)); assertTrue(result.isErr)
        assertEquals(1, recordingJwt.signCalls)
        assertTrue(snapshot().getValue("Subordinate").isEmpty())
        assertTrue(snapshot().getValue("SubordinateStatement").isEmpty())
    }
    @Test fun selectionChangedDuringManagedSigningCannotReturnPreparedStatement() = runTest {
        val account = account(); val row = select(account); val replacement = key(account)
        recordingJwt.afterSigning = {
            val changed = SetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, accountSigningKeyQueries)
                .execute(SetAccountSigningKeySelectionArgs(account, identifier, 1L, replacement.id))
            assertTrue(changed.isOk)
        }
        val result = command().execute(args(account, row)); assertTrue(result.isErr)
        assertEquals(1, recordingJwt.signCalls)
        assertTrue(snapshot().getValue("Subordinate").isEmpty())
        assertTrue(snapshot().getValue("SubordinateStatement").isEmpty())
    }
    @Test fun persistedAlgorithmIncompatibleWithSelectedKeyIsRejectedBeforeSigning() = runTest {
        val account = account(); val row = select(account)
        source.connection.use { connection -> connection.prepareStatement("UPDATE Jwk SET alg = 'RS256' WHERE id = ?").use {
            it.setObject(1, UUID.fromString(row.id)); assertEquals(1, it.executeUpdate())
        } }
        val before = snapshot(); assertTrue(command().execute(args(account, row)).isErr)
        assertEquals(0, recordingJwt.signCalls); assertEquals(before, snapshot())
    }
    @Test fun missingManagedProviderRefusesWithoutMutatingStore() = runTest {
        val account = account(); val row = select(account)
        source.connection.use { connection -> connection.prepareStatement("UPDATE Jwk SET kms = 'absent-managed-provider' WHERE id = ?").use {
            it.setObject(1, UUID.fromString(row.id)); assertEquals(1, it.executeUpdate())
        } }
        val before = snapshot(); assertTrue(command().execute(args(account, row)).isErr)
        assertEquals(before, snapshot())
    }

    @Test fun sameBodyAbaPolicyMutationDuringSigningIsCaughtByDurableRevision() = runTest {
        val account = account(); val row = select(account)
        val policyQueries = policies()
        val revisionQueries = com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevisionQueries(driver,
            com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevision.Adapter(JavaUuidStringAdapter))
        val body = Json.parseToJsonElement("""{"organization_name":{"default":"Reviewed"}}""").jsonObject
        val created = com.sphereon.openid.fed.services.command.metadataPolicy.CreateMetadataPolicyCommandImpl(execution,
            accountQueries, policyQueries, revisionQueries).execute(
                com.sphereon.openid.fed.services.command.metadataPolicy.CreateMetadataPolicyArgs(account, "openid_relying_party", body))
        assertTrue(created.isOk)
        recordingJwt.afterSigning = {
            val removed = com.sphereon.openid.fed.services.command.metadataPolicy.DeleteMetadataPolicyCommandImpl(execution,
                accountQueries, policyQueries, revisionQueries).execute(
                    com.sphereon.openid.fed.services.command.metadataPolicy.DeleteMetadataPolicyArgs(account, requireNotNull(created.value.id)))
            assertTrue(removed.isOk)
            val repeated = com.sphereon.openid.fed.services.command.metadataPolicy.CreateMetadataPolicyCommandImpl(execution,
                accountQueries, policyQueries, revisionQueries).execute(
                    com.sphereon.openid.fed.services.command.metadataPolicy.CreateMetadataPolicyArgs(account, "openid_relying_party", body))
            assertTrue(repeated.isOk)
        }
        val result = command().execute(args(account, row).copy(expectedMetadataPolicyRevisions = mapOf("openid_relying_party" to 1L)))
        assertTrue(result.isErr); assertEquals(1, recordingJwt.signCalls)
        assertEquals(3L, revisionQueries.findByAccountIdAndEntityType(account, "openid_relying_party").executeAsOne().revision)
        assertTrue(snapshot().getValue("Subordinate").isEmpty()); assertTrue(snapshot().getValue("SubordinateStatement").isEmpty())
    }
}
