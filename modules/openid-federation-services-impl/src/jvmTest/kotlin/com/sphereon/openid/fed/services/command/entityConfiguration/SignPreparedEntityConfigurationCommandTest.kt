package com.sphereon.openid.fed.services.command.entityConfiguration

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
class SignPreparedEntityConfigurationCommandTest {
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
    private lateinit var source: PGSimpleDataSource
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
        override suspend fun createJwsCompact(args: CreateJwsArgs): IdkResult<JwtCompactResult, com.sphereon.core.api.error.IdkError> {
            signCalls++
            return delegate.createJwsCompact(args)
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
        source = PGSimpleDataSource().apply {
            setUrl(schemaJdbcUrl)
            user = container.username
            password = container.password
        }
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
        val actorFailure = actorSessions.mapNotNull { runCatching { it.destroy() }.exceptionOrNull() }.firstOrNull()
        val sessionFailure = runCatching { if (::ownedSession.isInitialized) ownedSession.destroy() }.exceptionOrNull()
        val driverFailure = runCatching { if (::driver.isInitialized) driver.close() }.exceptionOrNull()
        if (actorFailure != null) {
            sessionFailure?.let { actorFailure.addSuppressed(it) }
            driverFailure?.let { actorFailure.addSuppressed(it) }
            throw actorFailure
        }
        if (sessionFailure != null) {
            driverFailure?.let { sessionFailure.addSuppressed(it) }
            throw sessionFailure
        }
        driverFailure?.let { throw it }
    }

    private fun account(entityIdentifier: String = identifier): String =
        accountQueries.create("prepared-" + UUID.randomUUID(), entityIdentifier).executeAsOne().id

    private suspend fun key(accountId: String): Jwk {
        val generated = keyManager.generateKeyAsync(alg = SignatureAlgorithm.ECDSA_SHA256)
        return jwkQueries.create(
            account_id = accountId,
            alg = "ES256",
            kid = requireNotNull(generated.kid),
            kms = generated.providerId,
            kms_key_ref = requireNotNull(generated.alias),
            key = generated.jose.publicJwk.toJsonString(),
        ).executeAsOne()
    }

    private fun updatePersistedJwkField(id: String, field: String, value: String?) {
        require(field in setOf("kid", "alg", "kms", "kms_key_ref", "key"))
        source.connection.use { connection ->
            connection.prepareStatement("UPDATE Jwk SET $field = ? WHERE id = ?").use { update ->
                update.setString(1, value)
                update.setObject(2, UUID.fromString(id))
                assertEquals(1, update.executeUpdate(), "the test must mutate exactly the selected persisted row")
            }
        }
    }

    private fun statement(
        entityIdentifier: String,
        advertisedKey: JsonObject,
        now: Long = getCurrentEpochTimeSeconds(),
    ): JsonObject = buildJsonObject {
        put("iss", entityIdentifier)
        put("sub", entityIdentifier)
        put("iat", now - 60)
        put("exp", now + 3600)
        put("jwks", buildJsonObject { put("keys", JsonArray(listOf(advertisedKey))) })
        put("authority_hints", JsonArray(listOf(JsonPrimitive("https://superior.example"))))
        put("metadata", buildJsonObject {
            put("federation_entity", buildJsonObject {
                put("organization_name", "Example")
                put("custom_nested", buildJsonObject { put("enabled", true) })
            })
        })
        put("custom_top_level", buildJsonObject { put("version", 7) })
    }

   private fun command(): SignPreparedEntityConfigurationCommandImpl =
        SignPreparedEntityConfigurationCommandImpl(
            execution, accountRepository, recordingJwt, jwkQueries, findSelectionCommand(),
        )

    private suspend fun selectedSigningKey(accountId: String): Jwk {
        val selected = key(accountId)
        val bound = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 0L, selected.id),
        )
        assertTrue(bound.isOk, "the signer fixture must select its real persisted key")
        assertEquals(selected.id, bound.value.selectedKeyId)
        assertEquals(1L, bound.value.revision)
        return selected
    }

    private fun preparedArgs(accountId: String, selectedKeyId: String, payload: JsonObject) =
        SignPreparedEntityConfigurationArgs(
            accountId = accountId,
            selectedKeyId = selectedKeyId,
            statement = payload,
            expectedSelectionRevision = selection(accountId)?.revision ?: 0L,
        )

    private fun selectionCommand(keys: JwkQueries = jwkQueries): SetAccountSigningKeySelectionCommandImpl =
        SetAccountSigningKeySelectionCommandImpl(execution, accountQueries, keys, accountSigningKeyQueries)

    private fun revokeCommand(keys: JwkQueries = jwkQueries): RevokeKeyCommandImpl =
        RevokeKeyCommandImpl(execution, accountQueries, keys, accountSigningKeyQueries)

    private fun selection(accountId: String): AccountSigningKey? =
        accountSigningKeyQueries.findByAccountId(accountId).executeAsOneOrNull()

    private fun findSelectionCommand(accounts: AccountQueries = accountQueries): FindAccountSigningKeySelectionCommandImpl =
        FindAccountSigningKeySelectionCommandImpl(execution, accounts, accountSigningKeyQueries)

    private fun actorSource(actor: String): PGSimpleDataSource = PGSimpleDataSource().apply {
        setUrl(schemaJdbcUrl + "&ApplicationName=" + actor + "&options=-c%20statement_timeout%3D60000")
        user = dbUsername
        password = dbPassword
    }

    private fun actorExecution(): SessionExecution {
        val session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "selected-key-actor-" + UUID.randomUUID(), principalType = PrincipalType.ANONYMOUS,
        )
        actorSessions += session
        return session.asCoreApiServiceGraph().serviceExecution
    }

    private fun selectionCommand(driver: SqlDriver, actorExecution: SessionExecution) =
        SetAccountSigningKeySelectionCommandImpl(
            actorExecution,
            AccountQueries(driver, Account.Adapter(JavaUuidStringAdapter)),
            JwkQueries(driver, Jwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter)),
            AccountSigningKeyQueries(driver, AccountSigningKey.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter)),
        )

    private fun revokeCommand(driver: SqlDriver, actorExecution: SessionExecution) =
        RevokeKeyCommandImpl(
            actorExecution,
            AccountQueries(driver, Account.Adapter(JavaUuidStringAdapter)),
            JwkQueries(driver, Jwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter)),
            AccountSigningKeyQueries(driver, AccountSigningKey.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter)),
        )

    private suspend fun signAndVerifyUsingPersistedRoute(row: Jwk, payload: JsonObject): String {
        val publicKey = Json.parseToJsonElement(row.key).jsonObject
        val signed = jwtService.signPayload(
            payload = payload,
            header = JwtHeader(typ = "entity-statement+jwt", kid = requireNotNull(row.kid), alg = requireNotNull(row.alg)),
            kid = requireNotNull(row.kid),
            kmsKeyRef = row.kms_key_ref,
            kmsProviderId = row.kms,
        )
        assertTrue(signed.isOk, "the real persisted provider/alias/kid route must sign")
        val verified = jwtService.verifyJws(
            VerifyJwsArgs(
                jws = JwsCompact(signed.value),
                trustedJwks = buildJsonObject { put("keys", JsonArray(listOf(publicKey))) },
            ),
        )
        assertTrue(verified.isOk && verified.value.isValid, "the real signature must verify against the persisted key")
        return signed.value
    }

    private fun resolveCommand() =
        com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyCommandImpl(execution, findSelectionCommand(), jwkQueries)

    private suspend fun resolve(accountId: String) = resolveCommand().execute(
        com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyArgs(accountId, identifier),
    )

    @Test
    fun resolvedSigningKeyIsExactlyThePersistedSelectionWithoutAnyFallback() = runTest {
        val accountId = account()
        key(accountId)
        key(accountId)
        assertTrue(resolve(accountId).isErr, "Persisted keys without a selection must never be picked")

        val selected = selectedSigningKey(accountId)
        val resolved = resolve(accountId)
        assertTrue(resolved.isOk)
        assertEquals(selected.id, resolved.value.keyId)
        assertEquals(selected.kid, resolved.value.kid)
        assertEquals("ES256", resolved.value.alg)
        assertEquals(selected.kms_key_ref, resolved.value.kmsKeyRef)
        assertEquals(selected.kms, resolved.value.kms)
        assertEquals(1L, resolved.value.selectionRevision)

        updatePersistedJwkField(selected.id, "alg", null)
        assertTrue(resolve(accountId).isErr, "A selected key without an algorithm is refused, not defaulted")
        updatePersistedJwkField(selected.id, "alg", "ES256")
        updatePersistedJwkField(selected.id, "kid", null)
        assertTrue(resolve(accountId).isErr, "A selected key without a kid is refused")
    }

    @Test
    fun persistedSoftwareKmsRouteSignsAndVerifiesIndependentlyOfTheNewCommand() = runTest {
        val accountId = account()
        val selected = key(accountId)
        val payload = statement(identifier, Json.parseToJsonElement(selected.key).jsonObject)

        val compact = signAndVerifyUsingPersistedRoute(selected, payload)

        assertEquals(payload.filterKeys { it != "iat" && it != "exp" },
            decodeJWTComponents(compact).payload.filterKeys { it != "iat" && it != "exp" })
        assertEquals(0, recordingJwt.signCalls, "the independent helper must not exercise the new command")
    }

    @Test
    fun exactPersistedKeySignsUnchangedPreparedExtensionsWithRealSoftwareKms() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val publicKey = Json.parseToJsonElement(selected.key).jsonObject
        val payload = statement(identifier, publicKey)

        val result = command().execute(preparedArgs(accountId, selected.id, payload))

        assertTrue(result.isOk, "exact persisted selection should sign successfully")
        assertEquals(1, recordingJwt.signCalls)
        val decoded = decodeJWTComponents(result.value)
        assertEquals("entity-statement+jwt", decoded.header.typ)
        assertEquals(selected.kid, decoded.header.kid)
        assertEquals("ES256", decoded.header.alg)
        assertEquals(
            payload.filterKeys { it != "iat" && it != "exp" },
            decoded.payload.filterKeys { it != "iat" && it != "exp" },
        )
        assertFalse(decoded.payload.getValue("iat").jsonPrimitive.isString)
        assertFalse(decoded.payload.getValue("exp").jsonPrimitive.isString)
        assertEquals(payload.getValue("iat").jsonPrimitive.long, decoded.payload.getValue("iat").jsonPrimitive.long)
        assertEquals(payload.getValue("exp").jsonPrimitive.long, decoded.payload.getValue("exp").jsonPrimitive.long)
        val verified = jwtService.verifyJws(
            VerifyJwsArgs(
                jws = JwsCompact(result.value),
                trustedJwks = buildJsonObject { put("keys", JsonArray(listOf(publicKey))) },
            ),
        )
        assertTrue(verified.isOk && verified.value.isValid, "real signature must verify against the selected public key")
    }

    @Test
    fun preparedIssuerDifferentFromPersistedAccountRejectsBeforeSigning() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val payload = statement("https://other.example", Json.parseToJsonElement(selected.key).jsonObject)

        val result = command().execute(preparedArgs(accountId, selected.id, payload))

        assertTrue(result.isErr)
        assertEquals("invalid_request", result.error.code)
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun missingActiveAccountRejectsBeforeSigning() = runTest {
        val accountId = UUID.randomUUID().toString()
        val payload = statement(identifier, buildJsonObject { put("kty", "EC") })

        val result = command().execute(preparedArgs(accountId, UUID.randomUUID().toString(), payload))

        assertTrue(result.isErr)
        assertEquals(AccountNotFoundError.ERROR_CODE, result.error.code)
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun changedAdvertisedPublicMaterialCannotUseSelectedPersistedKey() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val alternate = key(accountId)
        assertTrue(alternate.id != selected.id)
        assertTrue(alternate.kid != selected.kid)
        val alternatePublic = Json.parseToJsonElement(alternate.key).jsonObject
        signAndVerifyUsingPersistedRoute(alternate, statement(identifier, alternatePublic))
        val changed = JsonObject(alternatePublic + ("kid" to JsonPrimitive(requireNotNull(selected.kid))))
        val payload = statement(identifier, changed)

        val result = command().execute(preparedArgs(accountId, selected.id, payload))

        assertTrue(result.isErr)
        assertEquals("invalid_request", result.error.code)
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun revokedSelectedKeyCannotSignEvenThoughItsHistoricalRawMaterialRemains() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val payload = statement(identifier, Json.parseToJsonElement(selected.key).jsonObject)
        jwkQueries.revoke("retired", selected.id).executeAsOne()
        assertTrue(jwkQueries.findById(selected.id).executeAsOneOrNull()?.revoked_at != null)

        val result = command().execute(preparedArgs(accountId, selected.id, payload))

        assertTrue(result.isErr)
        assertEquals("invalid_request", result.error.code)
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun selectedKeyOwnedByAnotherActiveAccountCannotSign() = runTest {
        val accountId = account()
        selectedSigningKey(accountId)
        val otherAccountId = account("https://other.example")
        val selected = key(otherAccountId)
        val payload = statement(identifier, Json.parseToJsonElement(selected.key).jsonObject)

        val result = command().execute(preparedArgs(accountId, selected.id, payload))

        assertTrue(result.isErr)
        assertEquals("invalid_request", result.error.code)
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun duplicateAdvertisedKidCannotSelectOneKeyForSigning() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val publicKey = Json.parseToJsonElement(selected.key).jsonObject
        val payload = JsonObject(statement(identifier, publicKey) +
            ("jwks" to buildJsonObject { put("keys", JsonArray(listOf(publicKey, publicKey))) }))

        val result = command().execute(preparedArgs(accountId, selected.id, payload))

        assertTrue(result.isErr)
        assertEquals("invalid_request", result.error.code)
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun incompletePersistedSigningRouteRejectsBeforeSigning() = runTest {
        for ((field, replacement) in listOf(
            "kid" to null,
            "alg" to null,
            "kms" to "",
            "kms_key_ref" to "",
        )) {
            val accountId = account()
            val selected = selectedSigningKey(accountId)
            val payload = statement(identifier, Json.parseToJsonElement(selected.key).jsonObject)
            updatePersistedJwkField(selected.id, field, replacement)
            val persisted = requireNotNull(jwkQueries.findById(selected.id).executeAsOneOrNull())
            val stored = when (field) {
                "kid" -> persisted.kid
                "alg" -> persisted.alg
                "kms" -> persisted.kms
                else -> persisted.kms_key_ref
            }
            assertEquals(replacement, stored, "the malformed $field row must actually be stored")

            val result = command().execute(preparedArgs(accountId, selected.id, payload))

            assertTrue(result.isErr, "missing/blank persisted $field must reject")
            assertEquals("invalid_request", result.error.code, "field=$field")
            assertEquals(0, recordingJwt.signCalls, "field=$field must reject before signing")
        }
    }

    @Test
    fun advertisedPrivateFieldPresenceCannotBeSigned() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val publicKey = Json.parseToJsonElement(selected.key).jsonObject
        val advertised = JsonObject(publicKey + ("d" to JsonNull))
        updatePersistedJwkField(selected.id, "key", advertised.toString())
        val persisted = requireNotNull(jwkQueries.findById(selected.id).executeAsOneOrNull())
        assertEquals(advertised, Json.parseToJsonElement(persisted.key).jsonObject)

        val result = command().execute(
            preparedArgs(accountId, selected.id, statement(identifier, advertised)),
        )

        assertTrue(result.isErr)
        assertEquals("invalid_request", result.error.code)
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun expiredPreparedStatementRejectsBeforeSigning() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val payload = statement(identifier, Json.parseToJsonElement(selected.key).jsonObject,
            now = getCurrentEpochTimeSeconds() - 7_200)

        val result = command().execute(preparedArgs(accountId, selected.id, payload))

        assertTrue(result.isErr)
        assertEquals("invalid_request", result.error.code)
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun unsupportedCriticalPreparedClaimRejectsBeforeSigning() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val publicKey = Json.parseToJsonElement(selected.key).jsonObject
        val payload = JsonObject(statement(identifier, publicKey) +
            ("crit" to JsonArray(listOf(JsonPrimitive("custom_top_level")))))

        val result = command().execute(preparedArgs(accountId, selected.id, payload))

        assertTrue(result.isErr)
        assertEquals("invalid_request", result.error.code)
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun publicVerifyOnlyKeyOperationsRemainEligibleForPrivateKmsSigning() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val publicKey = Json.parseToJsonElement(selected.key).jsonObject
        val advertised = JsonObject(publicKey +
            ("key_ops" to JsonArray(listOf(JsonPrimitive("verify")))))
        updatePersistedJwkField(selected.id, "key", advertised.toString())
        val persisted = requireNotNull(jwkQueries.findById(selected.id).executeAsOneOrNull())
        assertEquals(advertised, Json.parseToJsonElement(persisted.key).jsonObject)
        val payload = statement(identifier, advertised)
        signAndVerifyUsingPersistedRoute(persisted, payload)

        val result = command().execute(preparedArgs(accountId, selected.id, payload))

        assertTrue(result.isOk, "public key_ops=verify must not be mistaken for the private KMS signing policy")
        assertEquals(1, recordingJwt.signCalls)
        val verified = jwtService.verifyJws(
            VerifyJwsArgs(
                jws = JwsCompact(result.value),
                trustedJwks = buildJsonObject { put("keys", JsonArray(listOf(advertised))) },
            ),
        )
        assertTrue(verified.isOk && verified.value.isValid)
    }

    @Test
    fun foreignAccountPublicKeyCannotBeAdvertisedAlongsideSelectedAccountKey() = runTest {
        val accountId = account()
        val foreignAccountId = account("https://foreign.example")
        val selected = selectedSigningKey(accountId)
        val foreign = key(foreignAccountId)
        assertTrue(selected.id != foreign.id)
        assertTrue(selected.kid != foreign.kid)
        val selectedPublic = Json.parseToJsonElement(selected.key).jsonObject
        val foreignPublic = Json.parseToJsonElement(foreign.key).jsonObject
        signAndVerifyUsingPersistedRoute(foreign, statement("https://foreign.example", foreignPublic))
        val payload = JsonObject(
            statement(identifier, selectedPublic) +
                ("jwks" to buildJsonObject {
                    put("keys", JsonArray(listOf(selectedPublic, foreignPublic)))
                }),
        )

        val result = command().execute(preparedArgs(accountId, selected.id, payload))

        assertTrue(result.isErr)
        assertEquals("invalid_request", result.error.code)
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun callerMutationDuringAccountLookupCannotChangeSignedPreparedSnapshot() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val publicKey = Json.parseToJsonElement(selected.key).jsonObject
        val originalSnapshot = statement(identifier, publicKey)
        val backing = originalSnapshot.toMutableMap()
        val metadataBacking = originalSnapshot.getValue("metadata").jsonObject.toMutableMap()
        val federationBacking = metadataBacking.getValue("federation_entity").jsonObject.toMutableMap()
        val customNestedBacking = federationBacking.getValue("custom_nested").jsonObject.toMutableMap()
        federationBacking["custom_nested"] = JsonObject(customNestedBacking)
        metadataBacking["federation_entity"] = JsonObject(federationBacking)
        backing["metadata"] = JsonObject(metadataBacking)
        val hintsBacking = (originalSnapshot.getValue("authority_hints") as JsonArray).toMutableList()
        backing["authority_hints"] = JsonArray(hintsBacking)
        val mutableStatement = JsonObject(backing)
        val lookupEntered = CompletableDeferred<Unit>()
        val resumeLookup = CompletableDeferred<Unit>()
        coEvery { accountRepository.findById(accountId) } coAnswers {
            lookupEntered.complete(Unit)
            resumeLookup.await()
            IdkResult.ok(accountQueries.findById(accountId).executeAsOneOrNull()?.toAccountDTO())
        }
        val commandCall = async {
            command().execute(preparedArgs(accountId, selected.id, mutableStatement))
        }

        try {
            runCurrent()
            assertTrue(
                lookupEntered.isCompleted && !commandCall.isCompleted,
                "command should suspend on the first persisted-account lookup",
            )
            customNestedBacking["enabled"] = JsonPrimitive(false)
            federationBacking["organization_name"] = JsonPrimitive("mutated during lookup")
            hintsBacking += JsonPrimitive("https://unexpected.example")
            backing["custom_top_level"] = buildJsonObject { put("version", 99) }
            assertFalse(mutableStatement["metadata"] == originalSnapshot["metadata"])
            assertFalse(mutableStatement["authority_hints"] == originalSnapshot["authority_hints"])
            assertFalse(mutableStatement["custom_top_level"] == originalSnapshot["custom_top_level"])
            resumeLookup.complete(Unit)
            val result = commandCall.await()

            assertTrue(result.isOk, "valid prepared input should sign after the authoritative account lookup")
            assertEquals(1, recordingJwt.signCalls)
            val decoded = decodeJWTComponents(result.value)
            assertEquals(
                originalSnapshot.filterKeys { it != "iat" && it != "exp" },
                decoded.payload.filterKeys { it != "iat" && it != "exp" },
                "the signed token must preserve the original nested object and list, not a shallow root copy",
            )
            val verified = jwtService.verifyJws(
                VerifyJwsArgs(
                    jws = JwsCompact(result.value),
                    trustedJwks = buildJsonObject { put("keys", JsonArray(listOf(publicKey))) },
                ),
            )
            assertTrue(verified.isOk && verified.value.isValid, "the real signature must verify against the persisted public key")
        } finally {
            resumeLookup.complete(Unit)
            withContext(NonCancellable) {
                withTimeout(5.seconds) {
                    commandCall.cancel()
                    commandCall.join()
                }
            }
        }
    }

    @Test
    fun malformedNonFinitePreparedSnapshotRejectsBeforeRepositoryLookupOrSigning() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val publicKey = Json.parseToJsonElement(selected.key).jsonObject
        val payload = JsonObject(statement(identifier, publicKey) +
            ("nonfinite_extension" to JsonPrimitive(Double.NaN)))
        val serializerFailure = runCatching {
            Json.encodeToString(JsonObject.serializer(), payload)
        }.exceptionOrNull()
        assertNotNull(serializerFailure, "the existing serializer must reject this malformed snapshot fixture")
        var accountLookups = 0
        coEvery { accountRepository.findById(accountId) } coAnswers {
            accountLookups++
            IdkResult.ok(accountQueries.findById(accountId).executeAsOneOrNull()?.toAccountDTO())
        }

        val result = command().execute(preparedArgs(accountId, selected.id, payload))

        assertTrue(result.isErr)
        assertEquals("invalid_request", result.error.code)
        assertEquals(0, accountLookups, "invalid snapshot must reject before the first repository suspension")
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun signerCancellationEscapesByIdentityBeforeCommandErrorMapping() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val payload = statement(identifier, Json.parseToJsonElement(selected.key).jsonObject)
        val cancellation = CancellationException("cancel prepared federation signing")
        var signerEntered = false
        val throwingJwt = object : JwtService by jwtService {
            override suspend fun createJwsCompact(
                args: CreateJwsArgs,
            ): IdkResult<JwtCompactResult, com.sphereon.core.api.error.IdkError> {
                signerEntered = true
                throw cancellation
            }
        }
        val command = SignPreparedEntityConfigurationCommandImpl(
            execution,
            accountRepository,
            throwingJwt,
            jwkQueries,
            findSelectionCommand(),
        )

        val escaped = assertFailsWith<CancellationException> {
            command.execute(preparedArgs(accountId, selected.id, payload))
        }

        assertTrue(signerEntered, "the valid persisted route must reach the injected signer")
        assertSameThrowableInCauseChain(escaped, cancellation)
    }

    @Test
    fun currentSelectedRevisionSignsWithRealSoftwareKms() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val advanced = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 1L, selected.id),
        )
        assertTrue(advanced.isOk)
        assertEquals(2L, advanced.value.revision)
        val publicKey = Json.parseToJsonElement(selected.key).jsonObject
        val payload = statement(identifier, publicKey)

        val result = command().execute(preparedArgs(accountId, selected.id, payload))

        assertTrue(result.isOk, "the current selected UUID and revision should sign")
        assertEquals(1, recordingJwt.signCalls)
        assertEquals(advanced.value.revision, selection(accountId)?.revision)
        val verified = jwtService.verifyJws(
            VerifyJwsArgs(
                jws = JwsCompact(result.value),
                trustedJwks = buildJsonObject { put("keys", JsonArray(listOf(publicKey))) },
            ),
        )
        assertTrue(verified.isOk && verified.value.isValid)
    }

    @Test
    fun absentSelectionCannotSignAnOtherwiseActiveKey() = runTest {
        val accountId = account()
        val selected = key(accountId)
        assertNull(selection(accountId))
        val payload = statement(identifier, Json.parseToJsonElement(selected.key).jsonObject)

        val result = command().execute(
            SignPreparedEntityConfigurationArgs(accountId, selected.id, payload, 1L),
        )

        assertTrue(result.isErr, "absence is not an implicit first-key selection")
        assertTrue(result.error is SelectedSigningKeyConflictError)
        assertEquals(409, result.error.httpStatus.value)
        assertNull(selection(accountId))
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun staleRevisionCannotSignTheStillSelectedKey() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val payload = statement(identifier, Json.parseToJsonElement(selected.key).jsonObject)
        val prepared = preparedArgs(accountId, selected.id, payload)
        val advanced = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 1L, selected.id),
        )
        assertTrue(advanced.isOk)
        assertEquals(2L, advanced.value.revision)

        val result = command().execute(prepared)

        assertTrue(result.isErr, "the same UUID at a new revision is a changed selection")
        assertTrue(result.error is SelectedSigningKeyConflictError)
        assertEquals(409, result.error.httpStatus.value)
        assertEquals(advanced.value.selectedKeyId, selection(accountId)?.jwk_id)
        assertEquals(advanced.value.revision, selection(accountId)?.revision)
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun rotatedSelectionCannotSignTheFormerActiveKey() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val replacement = key(accountId)
        val prepared = preparedArgs(
            accountId, selected.id,
            statement(identifier, Json.parseToJsonElement(selected.key).jsonObject),
        )
        val rotated = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 1L, replacement.id),
        )
        assertTrue(rotated.isOk)
        assertEquals(replacement.id, rotated.value.selectedKeyId)
        assertNull(jwkQueries.findById(selected.id).executeAsOneOrNull()?.revoked_at)

        val result = command().execute(prepared)

        assertTrue(result.isErr, "an active old key is not the current selected key")
        assertTrue(result.error is SelectedSigningKeyConflictError)
        assertEquals(409, result.error.httpStatus.value)
        assertEquals(replacement.id, selection(accountId)?.jwk_id)
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun clearedSelectionCannotSignTheFormerActiveKey() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val prepared = preparedArgs(
            accountId, selected.id,
            statement(identifier, Json.parseToJsonElement(selected.key).jsonObject),
        )
        val cleared = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 1L, null),
        )
        assertTrue(cleared.isOk)
        assertNull(cleared.value.selectedKeyId)
        assertNull(jwkQueries.findById(selected.id).executeAsOneOrNull()?.revoked_at)

        val result = command().execute(prepared)

        assertTrue(result.isErr, "an active key cannot sign after explicit selection clear")
        assertTrue(result.error is SelectedSigningKeyConflictError)
        assertEquals(409, result.error.httpStatus.value)
        assertNull(selection(accountId)?.jwk_id)
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun abaSelectionCannotReuseAnOldPreparedRevision() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val replacement = key(accountId)
        val prepared = preparedArgs(
            accountId, selected.id,
            statement(identifier, Json.parseToJsonElement(selected.key).jsonObject),
        )
        val away = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 1L, replacement.id),
        )
        assertTrue(away.isOk)
        val back = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, away.value.revision, selected.id),
        )
        assertTrue(back.isOk)
        assertEquals(selected.id, back.value.selectedKeyId)
        assertEquals(3L, back.value.revision)

        val result = command().execute(prepared)

        assertTrue(result.isErr, "the same UUID after rotate-away-and-back is a different binding revision")
        assertTrue(result.error is SelectedSigningKeyConflictError)
        assertEquals(409, result.error.httpStatus.value)
        assertEquals(selected.id, selection(accountId)?.jwk_id)
        assertEquals(3L, selection(accountId)?.revision)
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun terminalSameRevisionClearCannotReuseTheFormerSelectedIdentity() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val terminal = accountSigningKeyQueries.upsert(accountId, selected.id, Long.MAX_VALUE).executeAsOne()
        assertEquals(selected.id, terminal.jwk_id)
        val payload = statement(identifier, Json.parseToJsonElement(selected.key).jsonObject)
        val prepared = SignPreparedEntityConfigurationArgs(accountId, selected.id, payload, Long.MAX_VALUE)
        val cleared = accountSigningKeyQueries.upsert(accountId, null, Long.MAX_VALUE).executeAsOne()
        assertNull(cleared.jwk_id)
        assertEquals(Long.MAX_VALUE, cleared.revision)
        assertNull(jwkQueries.findById(selected.id).executeAsOneOrNull()?.revoked_at)

        val result = command().execute(prepared)

        assertTrue(result.isErr, "matching MAX revision cannot substitute a cleared selected UUID")
        assertTrue(result.error is SelectedSigningKeyConflictError)
        assertEquals(409, result.error.httpStatus.value)
        assertEquals(cleared, selection(accountId))
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun negativeExpectedSelectionRevisionRejectsBeforeKms() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val payload = statement(identifier, Json.parseToJsonElement(selected.key).jsonObject)
        val binding = requireNotNull(selection(accountId))

        val result = command().execute(
            SignPreparedEntityConfigurationArgs(accountId, selected.id, payload, -1L),
        )

        assertTrue(result.isErr, "negative expected revision is malformed input, not a stale binding")
        assertEquals("invalid_request", result.error.code)
        assertEquals(400, result.error.httpStatus.value)
        assertEquals(binding, selection(accountId))
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun formerActiveKeyCannotSignUsingTheCurrentReplacementRevision() = runTest {
        val accountId = account()
        val former = selectedSigningKey(accountId)
        val replacement = key(accountId)
        val rotated = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 1L, replacement.id),
        )
        assertTrue(rotated.isOk)
        assertEquals(replacement.id, rotated.value.selectedKeyId)
        assertEquals(2L, rotated.value.revision)
        assertNull(jwkQueries.findById(former.id).executeAsOneOrNull()?.revoked_at)
        val payload = statement(identifier, Json.parseToJsonElement(former.key).jsonObject)
        val prepared = SignPreparedEntityConfigurationArgs(
            accountId, former.id, payload, rotated.value.revision,
        )

        val result = command().execute(prepared)

        assertTrue(result.isErr, "the current revision cannot authorize a different active owned UUID")
        assertTrue(result.error is SelectedSigningKeyConflictError)
        assertEquals(409, result.error.httpStatus.value)
        assertEquals(replacement.id, selection(accountId)?.jwk_id)
        assertEquals(rotated.value.revision, selection(accountId)?.revision)
        assertEquals(0, recordingJwt.signCalls)
    }

    @Test
    fun currentTerminalRevisionSelectionStillSignsWithRealSoftwareKms() = runTest {
        val accountId = account()
        val selected = selectedSigningKey(accountId)
        val terminal = accountSigningKeyQueries.upsert(accountId, selected.id, Long.MAX_VALUE).executeAsOne()
        assertEquals(selected.id, terminal.jwk_id)
        assertEquals(Long.MAX_VALUE, terminal.revision)
        val publicKey = Json.parseToJsonElement(selected.key).jsonObject
        val payload = statement(identifier, publicKey)

        val result = command().execute(
            SignPreparedEntityConfigurationArgs(accountId, selected.id, payload, Long.MAX_VALUE),
        )

        assertTrue(result.isOk, "MAX blocks later setter transitions, not signing with the current binding")
        assertEquals(1, recordingJwt.signCalls)
        assertEquals(terminal, selection(accountId))
        val verified = jwtService.verifyJws(
            VerifyJwsArgs(
                jws = JwsCompact(result.value),
                trustedJwks = buildJsonObject { put("keys", JsonArray(listOf(publicKey))) },
            ),
        )
        assertTrue(verified.isOk && verified.value.isValid)
    }

    @Test
    fun migratedSelectionStartsAbsentAndDatabaseEnforcesOwnedKeyAndPositiveRevision() = runTest {
        val accountId = account()
        val foreignAccountId = account("https://foreign.example")
        val first = key(accountId)
        val second = key(accountId)
        val foreign = key(foreignAccountId)

        assertNull(selection(accountId), "migration must not guess a default among live keys")
        assertEquals(2, jwkQueries.findByAccountId(accountId).executeAsList().size)
        val valid = accountSigningKeyQueries.upsert(accountId, first.id, 1L).executeAsOne()
        assertEquals(accountId, valid.account_id)
        assertEquals(first.id, valid.jwk_id)
        assertEquals(1L, valid.revision)
        val foreignFailure = runCatching {
            accountSigningKeyQueries.upsert(accountId, foreign.id, 2L).executeAsOne()
        }.exceptionOrNull()
        assertNotNull(foreignFailure, "same-account composite FK must reject another account's key")
        assertSqlStateInCauseChain(foreignFailure, "23503")
        val invalidRevisionFailure = runCatching {
            accountSigningKeyQueries.upsert(accountId, second.id, 0L).executeAsOne()
        }.exceptionOrNull()
        assertNotNull(invalidRevisionFailure, "revision zero is reserved for the absent-row read sentinel")
        assertSqlStateInCauseChain(invalidRevisionFailure, "23514")
        val unchanged = requireNotNull(selection(accountId))
        assertEquals(first.id, unchanged.jwk_id)
        assertEquals(1L, unchanged.revision)
    }

    @Test
    fun exactActiveOwnedKeyCanBeSelectedAndRotatedWhileOtherKeysStayLive() = runTest {
        val accountId = account()
        val first = key(accountId)
        val second = key(accountId)

        val selectedFirst = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 0L, first.id),
        )
        assertTrue(selectedFirst.isOk, "the exact active owned persisted UUID should be selectable")
        assertEquals(accountId, selectedFirst.value.accountId)
        assertEquals(identifier, selectedFirst.value.entityIdentifier)
        assertEquals(first.id, selectedFirst.value.selectedKeyId)
        assertEquals(1L, selectedFirst.value.revision)

        val selectedSecond = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 1L, second.id),
        )
        assertTrue(selectedSecond.isOk, "an explicit rotation should replace only the selected binding")
        assertEquals(second.id, selectedSecond.value.selectedKeyId)
        assertEquals(2L, selectedSecond.value.revision)
        assertEquals(2, jwkQueries.findByAccountId(accountId).executeAsList().size)
        assertEquals(second.id, selection(accountId)?.jwk_id)
    }

    @Test
    fun everySuccessfulSameKeyOrClearTransitionRetainsAndAdvancesTheRevisionRow() = runTest {
        val accountId = account()
        val selectedKey = key(accountId)

        val firstClear = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 0L, null),
        )
        assertTrue(firstClear.isOk)
        assertEquals(1L, firstClear.value.revision, "clear on a never-selected account creates revision one")
        assertNull(firstClear.value.selectedKeyId)
        assertEquals(1L, selection(accountId)?.revision)

        val selected = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 1L, selectedKey.id),
        )
        assertTrue(selected.isOk)
        assertEquals(2L, selected.value.revision)
        val sameKey = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 2L, selectedKey.id),
        )
        assertTrue(sameKey.isOk)
        assertEquals(3L, sameKey.value.revision, "same-value transition still advances the revision")
        val cleared = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 3L, null),
        )
        assertTrue(cleared.isOk)
        assertNull(cleared.value.selectedKeyId)
        assertEquals(4L, cleared.value.revision)
        assertEquals(4L, selection(accountId)?.revision, "clear retains a nullable row; it never resets to absent")
    }

    @Test
    fun oldRevisionCannotWinAfterAbaRotationOrSelectThenClear() = runTest {
        val accountId = account()
        val first = key(accountId)
        val second = key(accountId)
        assertTrue(selectionCommand().execute(SetAccountSigningKeySelectionArgs(accountId, identifier, 0L, first.id)).isOk)
        assertTrue(selectionCommand().execute(SetAccountSigningKeySelectionArgs(accountId, identifier, 1L, second.id)).isOk)
        assertTrue(selectionCommand().execute(SetAccountSigningKeySelectionArgs(accountId, identifier, 2L, first.id)).isOk)

        val staleAba = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 1L, second.id),
        )
        assertTrue(staleAba.isErr)
        assertEquals("selected_signing_key_conflict", staleAba.error.code)
        assertEquals(409, staleAba.error.httpStatus.value)
        assertEquals(first.id, selection(accountId)?.jwk_id)
        assertEquals(3L, selection(accountId)?.revision)

        assertTrue(selectionCommand().execute(SetAccountSigningKeySelectionArgs(accountId, identifier, 3L, null)).isOk)
        val staleAbsent = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 0L, second.id),
        )
        assertTrue(staleAbsent.isErr)
        assertEquals("selected_signing_key_conflict", staleAbsent.error.code)
        assertEquals(409, staleAbsent.error.httpStatus.value)
        assertNull(selection(accountId)?.jwk_id)
        assertEquals(4L, selection(accountId)?.revision)
    }

    @Test
    fun missingAndForeignKeysAreNotSelectableAndDoNotChangeExistingSelection() = runTest {
        val accountId = account()
        val foreignAccountId = account("https://foreign.example")
        val selected = key(accountId)
        val foreign = key(foreignAccountId)
        accountSigningKeyQueries.upsert(accountId, selected.id, 1L).executeAsOne()

        val missing = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 1L, "00000000-0000-0000-0000-000000000099"),
        )
        assertTrue(missing.isErr)
        assertEquals("key_not_found", missing.error.code)
        assertEquals(selected.id, selection(accountId)?.jwk_id)
        assertEquals(1L, selection(accountId)?.revision)

        val foreignResult = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 1L, foreign.id),
        )
        assertTrue(foreignResult.isErr)
        assertEquals("key_not_found", foreignResult.error.code, "foreign ownership must not disclose key existence")
        assertEquals(selected.id, selection(accountId)?.jwk_id)
        assertEquals(1L, selection(accountId)?.revision)
    }

    @Test
    fun revokedKeyCannotBeSelectedAndLeavesBindingUnchanged() = runTest {
        val accountId = account()
        val current = key(accountId)
        val revoked = key(accountId)
        accountSigningKeyQueries.upsert(accountId, current.id, 1L).executeAsOne()
        jwkQueries.revoke("test-revocation", revoked.id).executeAsOne()

        val result = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 1L, revoked.id),
        )

        assertTrue(result.isErr)
        assertEquals("key_not_found", result.error.code)
        assertEquals(current.id, selection(accountId)?.jwk_id)
        assertEquals(1L, selection(accountId)?.revision)
    }

    @Test
    fun expectedEntityIdentifierMustMatchTheActiveAccountBeforeAnySelectionWrite() = runTest {
        val accountId = account()
        val selectedKey = key(accountId)
        val result = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, "https://different.example", 0L, selectedKey.id),
        )

        assertTrue(result.isErr)
        assertEquals("invalid_request", result.error.code)
        assertNull(selection(accountId))
    }

    @Test
    fun malformedSelectionInputsReturnInvalidRequestWithoutChangingTheBinding() = runTest {
        val accountId = account()
        val selectedKey = key(accountId)
        val before = accountSigningKeyQueries.upsert(accountId, selectedKey.id, 1L).executeAsOne()
        data class InvalidInput(
            val description: String,
            val accountId: String,
            val expectedEntityIdentifier: String,
            val selectedKeyId: String?,
        )
        val invalidInputs = listOf(
            InvalidInput("blank account UUID", "", identifier, selectedKey.id),
            InvalidInput("malformed account UUID", "not-a-uuid", identifier, selectedKey.id),
            InvalidInput("blank expected entity identifier", accountId, "", selectedKey.id),
            InvalidInput("blank non-null key UUID", accountId, identifier, ""),
            InvalidInput("malformed key UUID", accountId, identifier, "not-a-uuid"),
        )

        for (input in invalidInputs) {
            val result = selectionCommand().execute(
                SetAccountSigningKeySelectionArgs(
                    input.accountId, input.expectedEntityIdentifier, 1L, input.selectedKeyId,
                ),
            )

            assertTrue(result.isErr, input.description)
            assertEquals("invalid_request", result.error.code, input.description)
            assertEquals(before, selection(accountId), "${input.description} must not mutate the full row")
        }
    }

    @Test
    fun deletedAccountCannotReceiveASelection() = runTest {
        val accountId = account()
        val selectedKey = key(accountId)
        accountQueries.delete(accountId).executeAsOne()

        val result = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, 0L, selectedKey.id),
        )

        assertTrue(result.isErr)
        assertEquals("account_not_found", result.error.code)
        assertNull(selection(accountId))
    }

    @Test
    fun validButNeverCreatedAccountCannotReceiveAnExistingAccountsKeySelection() = runTest {
        val existingAccountId = account()
        val existingKey = key(existingAccountId)
        val existingBinding = accountSigningKeyQueries.upsert(existingAccountId, existingKey.id, 4L).executeAsOne()
        val nonexistentAccountId = UUID.randomUUID().toString()

        val result = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(nonexistentAccountId, identifier, 0L, existingKey.id),
        )

        assertTrue(result.isErr)
        assertEquals("account_not_found", result.error.code)
        assertNull(selection(nonexistentAccountId))
        assertEquals(existingBinding, selection(existingAccountId))
    }

    @Test
    fun negativeExpectedRevisionAndRevisionOverflowAreRejectedWithoutMutation() = runTest {
        val accountId = account()
        val selectedKey = key(accountId)
        val negative = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, -1L, selectedKey.id),
        )
        assertTrue(negative.isErr)
        assertEquals("invalid_request", negative.error.code)
        assertNull(selection(accountId))

        val maxRevision = accountSigningKeyQueries.upsert(accountId, null, Long.MAX_VALUE).executeAsOne()
        assertEquals(Long.MAX_VALUE, maxRevision.revision)
        val overflow = selectionCommand().execute(
            SetAccountSigningKeySelectionArgs(accountId, identifier, Long.MAX_VALUE, selectedKey.id),
        )
        assertTrue(overflow.isErr)
        assertEquals("selected_signing_key_conflict", overflow.error.code)
        assertEquals(409, overflow.error.httpStatus.value)
        assertNull(selection(accountId)?.jwk_id)
        assertEquals(Long.MAX_VALUE, selection(accountId)?.revision)
    }

    @Test
    fun selectionQueryCancellationEscapesWithItsOriginalIdentity() = runTest {
        val accountId = account()
        val selectedKey = key(accountId)
        val cancellation = CancellationException("cancel selected signing-key lookup")
        val cancellingQueries = spyk(jwkQueries)
        every { cancellingQueries.findById(selectedKey.id) } throws cancellation

        val escaped = assertFailsWith<CancellationException> {
            SetAccountSigningKeySelectionCommandImpl(
                execution, accountQueries, cancellingQueries, accountSigningKeyQueries,
            ).execute(SetAccountSigningKeySelectionArgs(accountId, identifier, 0L, selectedKey.id))
        }

        assertSameThrowableInCauseChain(escaped, cancellation)
        assertNull(selection(accountId))
    }

    @Test
    fun revokingSelectedKeyClearsBindingAndAdvancesRevisionAtomically() = runTest {
        val accountId = account()
        val selectedKey = key(accountId)
        accountSigningKeyQueries.upsert(accountId, selectedKey.id, 7L).executeAsOne()

        val revoked = revokeCommand().execute(RevokeKeyArgs(accountId, selectedKey.id, "rotation complete"))

        assertTrue(revoked.isOk)
        val storedKey = requireNotNull(jwkQueries.findById(selectedKey.id).executeAsOneOrNull())
        assertNotNull(storedKey.revoked_at)
        val storedSelection = requireNotNull(selection(accountId))
        assertNull(storedSelection.jwk_id)
        assertEquals(8L, storedSelection.revision)
    }

    @Test
    fun revokeQueryCancellationEscapesByIdentity() = runTest {
        val accountId = account()
        val selectedKey = key(accountId)
        val cancellation = CancellationException("cancel selected-key revocation")
        val cancellingQueries = spyk(jwkQueries)
        every { cancellingQueries.revoke(any(), selectedKey.id) } throws cancellation

        val escaped = assertFailsWith<CancellationException> {
            revokeCommand(cancellingQueries).execute(RevokeKeyArgs(accountId, selectedKey.id, "test"))
        }

        assertSameThrowableInCauseChain(escaped, cancellation)
        assertNull(selection(accountId))
        assertEquals(null, jwkQueries.findById(selectedKey.id).executeAsOneOrNull()?.revoked_at)
    }

    @Test
    fun revokingAnUnselectedKeyPreservesTheSelectedKeyBindingRow() = runTest {
        val accountId = account()
        val selectedKey = key(accountId)
        val otherKey = key(accountId)
        val before = accountSigningKeyQueries.upsert(accountId, selectedKey.id, 7L).executeAsOne()

        val revoked = revokeCommand().execute(RevokeKeyArgs(accountId, otherKey.id, "rotate other key"))

        assertTrue(revoked.isOk)
        assertNotNull(jwkQueries.findById(otherKey.id).executeAsOneOrNull()?.revoked_at)
        assertNull(jwkQueries.findById(selectedKey.id).executeAsOneOrNull()?.revoked_at)
        assertEquals(before, selection(accountId), "revoking A must preserve the entire binding row for B")
    }

    @Test
    fun revokingAnActiveKeyWhenNoSelectionExistsDoesNotCreateABinding() = runTest {
        val accountId = account()
        val neverSelectedKey = key(accountId)
        assertNull(neverSelectedKey.revoked_at)
        assertNull(selection(accountId))

        val revoked = revokeCommand().execute(RevokeKeyArgs(accountId, neverSelectedKey.id, "retire unused key"))

        assertTrue(revoked.isOk)
        assertNotNull(jwkQueries.findById(neverSelectedKey.id).executeAsOneOrNull()?.revoked_at)
        assertNull(selection(accountId), "revoking an unselected key must not create a binding row")
    }

    @Test
    fun terminalSelectedKeyRevocationClearsAtMaxAndSetterAttemptsRemainConflicted() = runTest {
        val accountId = account()
        val unrelatedAccountId = account("https://unrelated.example")
        val selectedKey = key(accountId)
        val anotherActiveKey = key(accountId)
        val unrelatedKey = key(unrelatedAccountId)
        val terminalRevision = Long.MAX_VALUE
        assertNull(selectedKey.revoked_at)
        assertNull(anotherActiveKey.revoked_at)
        val initialBinding = accountSigningKeyQueries.upsert(accountId, selectedKey.id, terminalRevision).executeAsOne()
        assertEquals(selectedKey.id, initialBinding.jwk_id)
        assertEquals(terminalRevision, initialBinding.revision)
        val unrelatedBinding = accountSigningKeyQueries.upsert(unrelatedAccountId, unrelatedKey.id, 9L).executeAsOne()
        val unrelatedAccount = requireNotNull(accountQueries.findById(unrelatedAccountId).executeAsOneOrNull())
        val unrelatedKeys = jwkQueries.findByAccountId(unrelatedAccountId).executeAsList()
        assertEquals(listOf(unrelatedKey), unrelatedKeys)

        val revoked = revokeCommand().execute(RevokeKeyArgs(accountId, selectedKey.id, "terminal rotation"))

        assertTrue(revoked.isOk)
        assertNotNull(jwkQueries.findById(selectedKey.id).executeAsOneOrNull()?.revoked_at)
        val terminalBinding = requireNotNull(selection(accountId))
        assertEquals(accountId, terminalBinding.account_id)
        assertNull(terminalBinding.jwk_id)
        assertEquals(terminalRevision, terminalBinding.revision)
        assertEquals(unrelatedBinding, selection(unrelatedAccountId))
        assertEquals(unrelatedAccount, accountQueries.findById(unrelatedAccountId).executeAsOneOrNull())
        assertEquals(unrelatedKeys, jwkQueries.findByAccountId(unrelatedAccountId).executeAsList())

        for (attemptedSelection in listOf(selectedKey.id, anotherActiveKey.id, null)) {
            val result = selectionCommand().execute(
                SetAccountSigningKeySelectionArgs(accountId, identifier, terminalRevision, attemptedSelection),
            )

            assertTrue(result.isErr)
            assertEquals("selected_signing_key_conflict", result.error.code)
            assertEquals(409, result.error.httpStatus.value)
            assertEquals(terminalBinding, selection(accountId), "terminal MAX row must remain unchanged")
        }
        assertEquals(unrelatedBinding, selection(unrelatedAccountId))
        assertEquals(unrelatedAccount, accountQueries.findById(unrelatedAccountId).executeAsOneOrNull())
       assertEquals(unrelatedKeys, jwkQueries.findByAccountId(unrelatedAccountId).executeAsList())
    }

    @Test
    fun failedBindingClearRollsBackPriorKeyRevocationAtNormalAndTerminalRevisions() = runBlocking {
        source.connection.use { control ->
            control.executeSql("CREATE SEQUENCE rollback_after_revoke_probe")
            control.executeSql("""
                CREATE FUNCTION reject_binding_clear_after_revoke() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN
                  IF OLD.jwk_id IS NOT NULL AND NEW.jwk_id IS NULL THEN
                    IF NOT EXISTS (SELECT 1 FROM Jwk WHERE id = OLD.jwk_id AND revoked_at IS NOT NULL) THEN
                      RAISE EXCEPTION 'binding clear was attempted before key revocation' USING ERRCODE = 'P0001';
                    END IF;
                    PERFORM nextval('rollback_after_revoke_probe');
                    RAISE EXCEPTION 'injected post-revoke binding failure' USING ERRCODE = 'P0001';
                  END IF;
                  RETURN NEW;
                END
                ${'$'}${'$'}
            """.trimIndent())
            control.executeSql("""
                CREATE TRIGGER reject_binding_clear_after_revoke BEFORE UPDATE ON AccountSigningKey
                FOR EACH ROW EXECUTE FUNCTION reject_binding_clear_after_revoke()
            """.trimIndent())
            assertEquals(false, control.probeCalled("rollback_after_revoke_probe"))
            for ((index, revision) in listOf(7L, Long.MAX_VALUE).withIndex()) {
                val accountId = account("https://rollback-$index.example")
                val selectedKey = key(accountId)
                val beforeBinding = accountSigningKeyQueries.upsert(accountId, selectedKey.id, revision).executeAsOne()
                val beforeKey = requireNotNull(jwkQueries.findById(selectedKey.id).executeAsOneOrNull())

                val rejected = revokeCommand().execute(RevokeKeyArgs(accountId, selectedKey.id, "injected rollback"))

                assertTrue(rejected.isErr, "the schema-local post-revoke failure must be controlled")
                assertSqlStateInCauseChain(requireNotNull(rejected.error.exception), "P0001")
                assertTrue(control.probeCalled("rollback_after_revoke_probe"),
                    "the first sequence value alone does not prove nextval was called")
                assertEquals(index + 1L, control.probeLastValue("rollback_after_revoke_probe"),
                    "the nontransactional sequence proves this particular post-revoke trigger fired")
                assertEquals(beforeKey, jwkQueries.findById(selectedKey.id).executeAsOneOrNull(),
                    "the entire revoked-key row must roll back at revision $revision")
                assertEquals(beforeBinding, selection(accountId),
                    "the entire selected-binding row must roll back at revision $revision")
            }
        }
    }

    @Test
    fun revokeAndStaleReselectionSerializeOnTheSameAccountWhileAnotherAccountProceeds() {
        val accountId = account("https://race.example")
        val otherAccountId = account("https://independent.example")
        val selectedKey = runBlocking { key(accountId) }
        val otherKey = runBlocking { key(otherAccountId) }
        val beforeBinding = accountSigningKeyQueries.upsert(accountId, selectedKey.id, 7L).executeAsOne()
        val runId = UUID.randomUUID().toString().replace("-", "")
        val revokerActor = "selected-revoker-$runId"
        val setterActor = "selected-setter-$runId"
        val otherActor = "selected-other-$runId"
        val controlActor = "selected-control-$runId"
        val lockId = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
        var ownedRevokerDriver: SqlDriver? = null
        var ownedSetterDriver: SqlDriver? = null
        var ownedOtherDriver: SqlDriver? = null
        var ownedControl: Connection? = null
        var ownedWorkers: ExecutorService? = null
        var advisoryHeld = false
        var revokeFuture: CompletableFuture<IdkResult<TenantJwk, FederationError>>? = null
        var setterFuture: CompletableFuture<IdkResult<AccountSigningKeySelection, FederationError>>? = null
        var otherFuture: CompletableFuture<IdkResult<AccountSigningKeySelection, FederationError>>? = null
        var primaryFailure: Throwable? = null
        try {
            val revokerDriver = actorSource(revokerActor).asJdbcDriver().also { ownedRevokerDriver = it }
            val setterDriver = actorSource(setterActor).asJdbcDriver().also { ownedSetterDriver = it }
            val otherDriver = actorSource(otherActor).asJdbcDriver().also { ownedOtherDriver = it }
            val revokerExecution = actorExecution()
            val setterExecution = actorExecution()
            val otherExecution = actorExecution()
            val control = actorSource(controlActor).connection.also { ownedControl = it }
            val workers = Executors.newFixedThreadPool(3).also { ownedWorkers = it }
            control.executeSql("""
                CREATE FUNCTION pause_selected_binding_clear() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN
                  IF OLD.jwk_id IS NOT NULL AND NEW.jwk_id IS NULL THEN
                    IF NOT EXISTS (SELECT 1 FROM Jwk WHERE id = OLD.jwk_id AND revoked_at IS NOT NULL) THEN
                      RAISE EXCEPTION 'revoker reached binding clear before key revoke' USING ERRCODE = 'P0001';
                    END IF;
                    PERFORM pg_advisory_lock($lockId::bigint);
                    PERFORM pg_advisory_unlock($lockId::bigint);
                  END IF;
                  RETURN NEW;
                END
                ${'$'}${'$'}
            """.trimIndent())
            control.executeSql("""
                CREATE TRIGGER pause_selected_binding_clear BEFORE UPDATE ON AccountSigningKey
                FOR EACH ROW EXECUTE FUNCTION pause_selected_binding_clear()
            """.trimIndent())
            control.executeSql("SELECT pg_advisory_lock($lockId::bigint)")
            advisoryHeld = true
            val controlPid = control.backendPid()

            revokeFuture = CompletableFuture.supplyAsync({
                runBlocking { revokeCommand(revokerDriver, revokerExecution).execute(
                    RevokeKeyArgs(accountId, selectedKey.id, "serialize revoke"),
                ) }
            }, workers)
            var revokerPid: Int? = null
            control.awaitDbState("revoker post-revoke advisory wait") {
                revokerPid = waitingActorPid(revokerActor, "advisory", "AccountSigningKey")
                revokeFuture!!.isDone || revokerPid != null
            }
            assertFalse(revokeFuture!!.isDone, "revoker must reach its real post-revoke trigger")
            val observedRevokerPid = requireNotNull(revokerPid)
            assertTrue(control.isBlockedBy(observedRevokerPid, controlPid),
                "the control backend must block the revoker's post-revoke trigger")

            setterFuture = CompletableFuture.supplyAsync({
                runBlocking { selectionCommand(setterDriver, setterExecution).execute(
                    SetAccountSigningKeySelectionArgs(accountId, "https://race.example", beforeBinding.revision, selectedKey.id),
                ) }
            }, workers)
            otherFuture = CompletableFuture.supplyAsync({
                runBlocking { selectionCommand(otherDriver, otherExecution).execute(
                    SetAccountSigningKeySelectionArgs(otherAccountId, "https://independent.example", 0L, otherKey.id),
                ) }
            }, workers)
            var setterPid: Int? = null
            control.awaitDbState("setter Account-row lock wait") {
                setterPid = waitingAccountRowPid(setterActor)
                setterFuture!!.isDone || setterPid != null
            }
            assertFalse(setterFuture!!.isDone, "stale setter must wait for revoker's account-first transaction")
            val observedSetterPid = requireNotNull(setterPid)
            assertTrue(control.isBlockedBy(observedSetterPid, observedRevokerPid),
                "the observed Account-row wait must be blocked by the revoker, not a Jwk or binding row")
            val independent = otherFuture!!.get(15, TimeUnit.SECONDS)
            assertTrue(independent.isOk, "another account must make progress during the selected-account lock")
            assertEquals(otherKey.id, selection(otherAccountId)?.jwk_id)

            control.executeSql("SELECT pg_advisory_unlock($lockId::bigint)")
            advisoryHeld = false
            val revoked = revokeFuture!!.get(15, TimeUnit.SECONDS)
            val staleSetter = setterFuture!!.get(15, TimeUnit.SECONDS)
            assertTrue(revoked.isOk, "the revoker must finish after release")
            assertTrue(staleSetter.isErr, "an old read must never reselect the newly revoked key")
            assertTrue(staleSetter.error.code in setOf("selected_signing_key_conflict", "key_not_found"),
                "the stale operation must reject the old selected key")
            assertNotNull(jwkQueries.findById(selectedKey.id).executeAsOneOrNull()?.revoked_at)
            val finalBinding = requireNotNull(selection(accountId))
            assertNull(finalBinding.jwk_id)
            assertEquals(8L, finalBinding.revision)
            assertEquals(otherKey.id, selection(otherAccountId)?.jwk_id)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let { cleanupFailures += it }
            }
            if (advisoryHeld) cleanup { ownedControl?.executeSql("SELECT pg_advisory_unlock($lockId::bigint)") }
            if (revokeFuture?.isDone == false) cleanup { ownedControl?.cancelActor(revokerActor) }
            if (setterFuture?.isDone == false) cleanup { ownedControl?.cancelActor(setterActor) }
            if (otherFuture?.isDone == false) cleanup { ownedControl?.cancelActor(otherActor) }
            cleanup { ownedControl?.close() } // Also releases the test-owned advisory lock if unlock failed.
            cleanup { revokeFuture?.get(65, TimeUnit.SECONDS) }
            cleanup { setterFuture?.get(65, TimeUnit.SECONDS) }
            cleanup { otherFuture?.get(65, TimeUnit.SECONDS) }
            cleanup {
                ownedWorkers?.let { pool ->
                    pool.shutdown()
                    if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                        pool.shutdownNow()
                        check(pool.awaitTermination(5, TimeUnit.SECONDS)) { "test-owned PG actors did not terminate" }
                    }
                }
            }
            cleanup { ownedRevokerDriver?.close() }
            cleanup { ownedSetterDriver?.close() }
            cleanup { ownedOtherDriver?.close() }
            val failure = primaryFailure
            if (failure != null) cleanupFailures.forEach { failure.addSuppressed(it) }
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach { first.addSuppressed(it) }
                throw first
            }
        }
    }

    private fun Connection.executeSql(sql: String) { createStatement().use { it.execute(sql) } }

    private fun Connection.probeCalled(sequence: String): Boolean =
        createStatement().use { it.executeQuery("SELECT is_called FROM $sequence").use { rows -> rows.next() && rows.getBoolean(1) } }

    private fun Connection.probeLastValue(sequence: String): Long =
        createStatement().use { it.executeQuery("SELECT last_value FROM $sequence").use { rows ->
            assertTrue(rows.next())
            rows.getLong(1)
        } }

    private fun Connection.backendPid(): Int =
        createStatement().use { it.executeQuery("SELECT pg_backend_pid()").use { rows ->
            assertTrue(rows.next())
            rows.getInt(1)
        } }

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
            statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
        }

    private fun Connection.waitingAccountRowPid(actor: String): Int? =
        prepareStatement("""
            SELECT pid FROM pg_stat_activity
            WHERE datname = current_database() AND application_name = ?
              AND wait_event_type = 'Lock' AND wait_event IN ('transactionid', 'tuple')
              AND query ~* 'FROM[[:space:]]+Account[[:space:]]+WHERE[[:space:]]+id[[:space:]]*='
              AND query ~* 'FOR[[:space:]]+UPDATE'
            LIMIT 1
        """.trimIndent()).use { statement ->
            statement.setString(1, actor)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
        }

    private fun Connection.cancelActor(actor: String) {
        prepareStatement("""
            SELECT pg_cancel_backend(pid) FROM pg_stat_activity
            WHERE datname = current_database() AND application_name = ? AND pid <> pg_backend_pid()
        """.trimIndent()).use { statement ->
            statement.setString(1, actor)
            statement.executeQuery().use { rows -> while (rows.next()) rows.getBoolean(1) }
        }
    }

    private fun Connection.isBlockedBy(waitingPid: Int, blockingPid: Int): Boolean =
        prepareStatement("SELECT ? = ANY(pg_blocking_pids(?))").use { statement ->
            statement.setInt(1, blockingPid)
            statement.setInt(2, waitingPid)
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getBoolean(1) }
        }

    private fun Connection.awaitDbState(label: String, predicate: Connection.() -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(10) // PostgreSQL wait state, not elapsed time, is the assertion.
        }
        error("Timed out waiting for $label")
    }

    @Test
    fun absentSelectionReadsAsRevisionZeroWithoutInferringFromMultipleActiveKeysOrCreatingARow() = runTest {
        val accountId = account()
        val first = key(accountId)
        val second = key(accountId)
        val activeKeysBefore = jwkQueries.findByAccountId(accountId).executeAsList().toSet()
        assertEquals(setOf(first, second), activeKeysBefore)
        assertNull(selection(accountId))

        val result = findSelectionCommand().execute(
            FindAccountSigningKeySelectionArgs(accountId, identifier),
        )

        assertTrue(result.isOk)
        assertEquals(AccountSigningKeySelection(accountId, identifier, null, 0L), result.value)
        assertNull(selection(accountId), "absence is a read result, not a request to create a selection row")
        assertEquals(activeKeysBefore, jwkQueries.findByAccountId(accountId).executeAsList().toSet())
    }

    @Test
    fun selectedClearedAndTerminalMaxRowsReadBackExactlyWithoutMutation() = runTest {
        val accountId = account()
        val selected = key(accountId)
        val otherActive = key(accountId)
        val activeKeysBefore = jwkQueries.findByAccountId(accountId).executeAsList().toSet()
        assertEquals(setOf(selected, otherActive), activeKeysBefore)

        val selectedRow = accountSigningKeyQueries.upsert(accountId, selected.id, 17L).executeAsOne()
        val selectedRead = findSelectionCommand().execute(
            FindAccountSigningKeySelectionArgs(accountId, identifier),
        )
        assertTrue(selectedRead.isOk)
        assertEquals(AccountSigningKeySelection(accountId, identifier, selected.id, 17L), selectedRead.value)
        assertEquals(selectedRow, selection(accountId), "reading a selected row must not change its full stored state")

        val clearedRow = accountSigningKeyQueries.upsert(accountId, null, 18L).executeAsOne()
        val clearedRead = findSelectionCommand().execute(
            FindAccountSigningKeySelectionArgs(accountId, identifier),
        )
        assertTrue(clearedRead.isOk)
        assertEquals(AccountSigningKeySelection(accountId, identifier, null, 18L), clearedRead.value)
        assertEquals(clearedRow, selection(accountId), "clear remains a persisted positive revision")

        val terminalRow = accountSigningKeyQueries.upsert(accountId, otherActive.id, Long.MAX_VALUE).executeAsOne()
        val terminalRead = findSelectionCommand().execute(
            FindAccountSigningKeySelectionArgs(accountId, identifier),
        )
        assertTrue(terminalRead.isOk)
        assertEquals(
            AccountSigningKeySelection(accountId, identifier, otherActive.id, Long.MAX_VALUE),
            terminalRead.value,
        )
        assertEquals(terminalRow, selection(accountId), "reading MAX must neither normalize nor mutate it")
        assertEquals(activeKeysBefore, jwkQueries.findByAccountId(accountId).executeAsList().toSet())
    }

    @Test
    fun missingDeletedMismatchedAndMalformedSelectionReadsReturnTypedErrorsWithoutMutation() = runTest {
        val accountId = account()
        val activeKey = key(accountId)
        val existingBinding = accountSigningKeyQueries.upsert(accountId, activeKey.id, 9L).executeAsOne()
        val deletedAccountId = account("https://deleted.example")
        accountQueries.delete(deletedAccountId).executeAsOne()
        val missingAccountId = UUID.randomUUID().toString()
        data class ReadCase(
            val label: String,
            val accountId: String,
            val expectedEntityIdentifier: String,
            val errorCode: String,
        )
        val cases = listOf(
            ReadCase("blank account UUID", "", identifier, "invalid_request"),
            ReadCase("malformed account UUID", "not-a-uuid", identifier, "invalid_request"),
            ReadCase("blank expected identifier", accountId, "", "invalid_request"),
            ReadCase("mismatched expected identifier", accountId, "https://other.example", "invalid_request"),
            ReadCase("missing account", missingAccountId, identifier, "account_not_found"),
            ReadCase("deleted account", deletedAccountId, "https://deleted.example", "account_not_found"),
        )

        for (case in cases) {
            val result = findSelectionCommand().execute(
                FindAccountSigningKeySelectionArgs(case.accountId, case.expectedEntityIdentifier),
            )
            assertTrue(result.isErr, case.label)
            assertEquals(case.errorCode, result.error.code, case.label)
            assertEquals(existingBinding, selection(accountId), "${case.label} must not mutate the existing row")
            assertNull(selection(deletedAccountId))
            assertNull(selection(missingAccountId))
        }
    }

    @Test
    fun selectionReadQueryCancellationEscapesWithItsOriginalIdentity() = runTest {
        val accountId = account()
        val cancellation = CancellationException("cancel selected-signing-key read")
        val cancellingAccounts = spyk(accountQueries)
        every { cancellingAccounts.findActiveForUpdate(accountId) } throws cancellation

        val escaped = assertFailsWith<CancellationException> {
            findSelectionCommand(cancellingAccounts).execute(
                FindAccountSigningKeySelectionArgs(accountId, identifier),
            )
        }

        assertSameThrowableInCauseChain(escaped, cancellation)
        assertNull(selection(accountId), "cancelled read must not materialize a selection")
    }

    @Test
    fun schemaTwentyOneToTwentyTwoUpgradePreservesExistingAccountAndKeyRowsWithoutSelectionInference() = runTest {
        val container = requireNotNull(postgres)
        val schema = "selection_upgrade_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        val separator = if ('?' in container.jdbcUrl) '&' else '?'
        val upgradeSource = PGSimpleDataSource().apply {
            setUrl(container.jdbcUrl + separator + "currentSchema=" + schema)
            user = container.username
            password = container.password
        }
        val upgradeDriver = upgradeSource.asJdbcDriver()
        try {
            val baseline = Database.Schema.version - 1
            assertEquals(21L, baseline, "the generated pre-migration schema version is 21")
            Database.Schema.migrate(upgradeDriver, 0L, baseline)
            val oldAccountQueries = AccountQueries(upgradeDriver, Account.Adapter(JavaUuidStringAdapter))
            val oldJwkQueries = JwkQueries(upgradeDriver, Jwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter))
            val oldAccount = oldAccountQueries.create("upgrade-" + UUID.randomUUID(), identifier).executeAsOne()
            val generated = keyManager.generateKeyAsync(alg = SignatureAlgorithm.ECDSA_SHA256)
            val oldJwk = oldJwkQueries.create(
                account_id = oldAccount.id,
                alg = "ES256",
                kid = requireNotNull(generated.kid),
                kms = generated.providerId,
                kms_key_ref = requireNotNull(generated.alias),
                key = generated.jose.publicJwk.toJsonString(),
            ).executeAsOne()

            Database.Schema.migrate(upgradeDriver, baseline, Database.Schema.version)

            assertEquals(oldAccount, oldAccountQueries.findById(oldAccount.id).executeAsOneOrNull())
            assertEquals(oldJwk, oldJwkQueries.findById(oldJwk.id).executeAsOneOrNull())
            val upgradedSelections = AccountSigningKeyQueries(
                upgradeDriver,
                AccountSigningKey.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            )
            assertNull(
                upgradedSelections.findByAccountId(oldAccount.id).executeAsOneOrNull(),
                "schema upgrade must not infer a signing-key selection from existing active keys",
            )
        } finally {
            upgradeDriver.close()
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun assertSqlStateInCauseChain(actual: Throwable, expected: String) {
        val seen = IdentityHashMap<Throwable, Boolean>()
        var current: Throwable? = actual
        while (current != null && seen.put(current, true) == null) {
            if (current is SQLException && current.sqlState == expected) return
            current = current.cause
        }
        throw AssertionError("Expected SQLSTATE $expected somewhere in exception cause chain", actual)
    }

    private fun assertSameThrowableInCauseChain(actual: Throwable, expected: Throwable) {
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = actual
        while (current != null && seen.add(current)) {
            if (current === expected) return
            current = current.cause
        }
        assertTrue(false, "the original signer cancellation must remain in the escaped cause chain")
    }
}
