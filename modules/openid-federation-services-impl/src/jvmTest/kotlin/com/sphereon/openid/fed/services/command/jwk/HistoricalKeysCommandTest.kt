package com.sphereon.openid.fed.services.command.jwk

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.asCoreApiServiceGraph
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.kms.KeyManagerService
import com.sphereon.crypto.core.kms.asKeyManagerServiceGraph
import com.sphereon.crypto.jose.jws.JwsCompact
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.crypto.jose.jws.command.VerifyJwsArgs
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderConfig
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderFactoryImpl
import com.sphereon.di.context.PrincipalType
import com.sphereon.di.session.SessionInstance
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.persistence.Database
import com.sphereon.openid.fed.persistence.database.JavaUuidStringAdapter
import com.sphereon.openid.fed.persistence.models.Account
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.AccountSigningKey as AccountSigningKeyEntity
import com.sphereon.openid.fed.persistence.models.AccountSigningKeyQueries
import com.sphereon.openid.fed.persistence.models.Jwk
import com.sphereon.openid.fed.persistence.models.JwkQueries
import com.sphereon.openid.fed.services.command.resolution.createExactSuperiorEvidenceTestGraph
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Uses isolated PostgreSQL schemas and real software KMS signatures. */
class HistoricalKeysCommandTest {
    companion object {
        private var postgres: PostgreSQLContainer<Nothing>? = null

        @JvmStatic @BeforeClass
        fun startPostgres() {
            assertTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
                "Historical key tests require Docker and an isolated PostgreSQL container")
            postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                withDatabaseName("oidfed_historical_keys")
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

    private val identifier = "https://entity.example"
    private lateinit var driver: SqlDriver
    private lateinit var source: PGSimpleDataSource
    private lateinit var schema: String
    private lateinit var session: SessionInstance
    private lateinit var execution: SessionExecution
    private lateinit var accountQueries: AccountQueries
    private lateinit var jwkQueries: JwkQueries
    private lateinit var selectionQueries: AccountSigningKeyQueries
    private lateinit var keyManager: KeyManagerService
    private lateinit var jwtService: JwtService
    private lateinit var resolver: TenantContextResolver
    private lateinit var accountId: String
    private lateinit var signingKey: Jwk

    @Before
    fun setUp(): Unit = kotlinx.coroutines.runBlocking {
        schema = createSchema()
        source = dataSource(schema)
        driver = source.asJdbcDriver()
        Database.Schema.migrate(driver, 0L, Database.Schema.version)
        accountQueries = AccountQueries(driver, Account.Adapter(JavaUuidStringAdapter))
        jwkQueries = JwkQueries(driver, Jwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter))
        selectionQueries = AccountSigningKeyQueries(driver,
            AccountSigningKeyEntity.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter))
        val app = createExactSuperiorEvidenceTestGraph(this@HistoricalKeysCommandTest)
        session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "historical-" + UUID.randomUUID(), principalType = PrincipalType.ANONYMOUS)
        execution = session.asCoreApiServiceGraph().serviceExecution
        val provider = (app as SoftwareKmsProviderFactoryImpl.Graph).softwareKmsProvider.create(
            SoftwareKmsProviderConfig(id = "historical-provider-" + UUID.randomUUID()), execution)
        keyManager = session.graph.asKeyManagerServiceGraph().keyManagerService
        keyManager.registerProvider(provider, makeDefaultKms = true)
        jwtService = (session.graph as com.sphereon.crypto.jose.jws.JwtServiceImpl.Graph).jwtService

        accountId = accountQueries.create("historical-" + UUID.randomUUID(), identifier).executeAsOne().id
        signingKey = key(accountId)
        selectionQueries.upsert(accountId, signingKey.id, 1L).executeAsOne()
        resolver = mockk()
        coEvery { resolver.resolveIdentifier(accountId) } returns identifier
    }

    @After
    fun tearDown() {
        try {
            if (::session.isInitialized) session.destroy()
        } finally {
            try {
                if (::driver.isInitialized) driver.close()
            } finally {
                if (::schema.isInitialized) dropSchema(schema)
            }
        }
    }

    private fun createSchema(): String {
        val name = "historical_" + UUID.randomUUID().toString().replace("-", "")
        val container = requireNotNull(postgres)
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $name") }
        }
        return name
    }

    private fun dropSchema(name: String) {
        val container = requireNotNull(postgres)
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA $name CASCADE") }
        }
    }

    private fun dataSource(name: String): PGSimpleDataSource {
        val container = requireNotNull(postgres)
        val separator = if ('?' in container.jdbcUrl) '&' else '?'
        return PGSimpleDataSource().apply {
            setUrl(container.jdbcUrl + separator + "currentSchema=" + name)
            user = container.username
            password = container.password
        }
    }

    private suspend fun key(owner: String = accountId): Jwk {
        val generated = keyManager.generateKeyAsync(alg = SignatureAlgorithm.ECDSA_SHA256)
        return jwkQueries.create(owner, "ES256", requireNotNull(generated.kid), generated.providerId,
            requireNotNull(generated.alias), generated.jose.publicJwk.toJsonString()).executeAsOne()
    }

    private fun revoke() = RevokeKeyCommandImpl(execution, accountQueries, jwkQueries, selectionQueries)
    private fun retire() = RetireKeyCommandImpl(execution, accountQueries, jwkQueries, selectionQueries)
    private fun update() = UpdateKeyRevocationCommandImpl(execution, accountQueries, jwkQueries)
    private fun list() = ListHistoricalKeysCommandImpl(execution, accountQueries, jwkQueries)
    private fun resolveSigning() = ResolveAccountSigningKeyCommandImpl(execution,
        FindAccountSigningKeySelectionCommandImpl(execution, accountQueries, selectionQueries), jwkQueries)
    private fun signRequest() = SignAccountRequestJwtCommandImpl(
        execution, resolver, jwtService, resolveSigning(), accountQueries, jwkQueries)

    private fun row(id: String): Jwk = jwkQueries.findById(id).executeAsOne()
    private fun parts(jwt: String): Pair<JsonObject, JsonObject> {
        val parts = jwt.split('.')
        fun decode(part: String) = Json.parseToJsonElement(
            String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8)).jsonObject
        return decode(parts[0]) to decode(parts[1])
    }

    private suspend fun verify(jwt: String, key: Jwk = signingKey) {
        val verified = jwtService.verifyJws(VerifyJwsArgs(
            jws = JwsCompact(jwt),
            trustedJwks = buildJsonObject { put("keys", JsonArray(listOf(Json.parseToJsonElement(key.key)))) },
        ))
        assertTrue(verified.isOk && verified.value.isValid, "Signature must verify with the account key")
    }

    @Test
    fun revokeSupersededPublishesIndependentTimesAndWireReason() = runTest {
        val old = key()
        val revoked = revoke().execute(RevokeKeyArgs(accountId, old.id, "superseded", "rotated by ops"))
        assertTrue(revoked.isOk)
        assertEquals("rotated by ops", revoked.value.revokedReason)
        assertFalse(jwkQueries.findByAccountId(accountId).executeAsList().any { it.id == old.id })
        val result = list().execute(ListHistoricalKeysArgs(accountId))
        assertTrue(result.isOk)
        val historical = result.value.single()
        assertEquals(old.kid, historical.kid)
        assertEquals(row(old.id).created_at!!.toEpochSecond(ZoneOffset.UTC).toDouble(), historical.iat)
        assertEquals(historical.exp, historical.revoked!!.revokedAt)
        assertEquals("superseded", historical.revoked!!.reason)
        val before = row(old.id)
        assertTrue(revoke().execute(RevokeKeyArgs(accountId, old.id, "compromised")).isErr)
        assertEquals(before, row(old.id), "Revoking an already revoked key must change nothing")
    }

    @Test
    fun invalidReasonIsRefusedWithoutChangingTheKey() = runTest {
        val active = key()
        val before = row(active.id)
        val invalid = revoke().execute(RevokeKeyArgs(accountId, active.id, "Key Compromised"))
        assertTrue(invalid.isErr)
        assertEquals("invalid_request", invalid.error.code)
        assertEquals(before, row(active.id))
        assertTrue(list().execute(ListHistoricalKeysArgs(accountId)).value.isEmpty())
    }

    @Test
    fun retiredKeyCanBeRevokedSinceAnEarlierTimeWithoutMovingExpiry() = runTest {
        val old = key()
        assertTrue(retire().execute(RetireKeyArgs(accountId, old.id)).isOk)
        val retired = row(old.id)
        val retiredHistorical = list().execute(ListHistoricalKeysArgs(accountId)).value.single()
        assertNull(retiredHistorical.revoked)
        assertNull(jwkQueries.findActiveById(old.id).executeAsOneOrNull())
        val earlier = retired.expires_at!!.toEpochSecond(ZoneOffset.UTC) - 3600L
        assertTrue(update().execute(UpdateKeyRevocationArgs(accountId, old.id, "compromised", earlier)).isOk)
        val revoked = row(old.id)
        assertEquals(retired.expires_at, revoked.expires_at)
        val historical = list().execute(ListHistoricalKeysArgs(accountId)).value.single()
        assertEquals(retiredHistorical.exp, historical.exp)
        assertEquals(earlier.toDouble(), historical.revoked!!.revokedAt)
        assertEquals("compromised", historical.revoked!!.reason)
    }

    @Test
    fun revocationTimeOnlyMovesEarlierAndFutureTimeIsRefused() = runTest {
        val old = key()
        val earlier = System.currentTimeMillis() / 1000L - 3600L
        assertTrue(revoke().execute(RevokeKeyArgs(accountId, old.id, "superseded")).isOk)
        assertTrue(update().execute(UpdateKeyRevocationArgs(accountId, old.id, "compromised", earlier)).isOk)
        val before = row(old.id)
        assertTrue(update().execute(UpdateKeyRevocationArgs(accountId, old.id, "compromised", earlier + 60L)).isOk)
        assertEquals(before, row(old.id), "A later time must preserve the earlier stored time")
        assertTrue(update().execute(UpdateKeyRevocationArgs(accountId, old.id, "compromised", earlier - 60L)).isOk)
        assertEquals(earlier - 60L, row(old.id).revoked_at!!.toEpochSecond(ZoneOffset.UTC))
        val beforeFuture = row(old.id)
        val future = update().execute(UpdateKeyRevocationArgs(accountId, old.id, "compromised",
            System.currentTimeMillis() / 1000L + 3600L))
        assertTrue(future.isErr)
        assertEquals("invalid_request", future.error.code)
        assertEquals(beforeFuture, row(old.id))
        val active = key()
        val invalidState = update().execute(UpdateKeyRevocationArgs(accountId, active.id, "compromised"))
        assertTrue(invalidState.isErr)
        assertEquals("invalid_request", invalidState.error.code)
        assertEquals(active, row(active.id))
    }

    @Test
    fun signedHistoricalKeysContainRequiredClaimsAndOnlyPublicKeyMembers() = runTest {
        val old = key()
        val retired = key()
        val noReason = key()
        assertTrue(revoke().execute(RevokeKeyArgs(accountId, old.id, "superseded", "private ops description")).isOk)
        assertTrue(retire().execute(RetireKeyArgs(accountId, retired.id)).isOk)
        assertTrue(revoke().execute(RevokeKeyArgs(accountId, noReason.id, null, "another private description")).isOk)
        val result = GetFederationHistoricalKeysJwtCommandImpl(
            execution, list(), resolver, jwtService, resolveSigning(),
        ).execute(GetFederationHistoricalKeysJwtArgs(accountId))
        assertTrue(result.isOk)
        verify(result.value)
        val (header, payload) = parts(result.value)
        assertEquals("jwk-set+jwt", header["typ"]!!.jsonPrimitive.content)
        assertEquals(signingKey.kid, header["kid"]!!.jsonPrimitive.content)
        assertEquals("ES256", header["alg"]!!.jsonPrimitive.content)
        assertEquals(identifier, payload["iss"]!!.jsonPrimitive.content)
        assertTrue(payload["iat"]!!.jsonPrimitive.double > 0.0)
        val keys = payload["keys"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf(old.kid, retired.kid, noReason.kid), keys.map { it["kid"]!!.jsonPrimitive.content }.toSet())
        assertEquals(Json.parseToJsonElement(Json.encodeToString(list().execute(ListHistoricalKeysArgs(accountId)).value)),
            payload["keys"])
        for (public in keys) {
            assertNotNull(public["exp"])
            for (privateMember in listOf("d", "p", "q", "dp", "dq", "qi", "oth", "k", "revoked_reason", "description")) {
                assertFalse(public.containsKey(privateMember), "Historical keys must omit $privateMember")
            }
        }
        assertFalse(keys.single { it["kid"]!!.jsonPrimitive.content == retired.kid }.containsKey("revoked"))
        val omittedReason = keys.single { it["kid"]!!.jsonPrimitive.content == noReason.kid }["revoked"]!!.jsonObject
        assertFalse(omittedReason.containsKey("reason"))
        assertFalse(payload.toString().contains("private"))
    }

    @Test
    fun selectedSigningKeyCannotBeRetiredOrRevokedWithoutExplicitOverride() = runTest {
        val before = row(signingKey.id)
        val selection = selectionQueries.findByAccountId(accountId).executeAsOne()
        val retired = retire().execute(RetireKeyArgs(accountId, signingKey.id))
        val revoked = revoke().execute(RevokeKeyArgs(accountId, signingKey.id, "compromised"))
        assertTrue(retired.isErr)
        assertTrue(revoked.isErr)
        assertEquals("selected_signing_key_conflict", retired.error.code)
        assertEquals("selected_signing_key_conflict", revoked.error.code)
        assertEquals(before, row(signingKey.id))
        assertEquals(selection, selectionQueries.findByAccountId(accountId).executeAsOne())
        assertTrue(revoke().execute(RevokeKeyArgs(accountId, signingKey.id, "compromised", allowSelected = true)).isOk)
        assertNull(selectionQueries.findByAccountId(accountId).executeAsOne().jwk_id)
        assertTrue(resolveSigning().execute(ResolveAccountSigningKeyArgs(accountId, identifier)).isErr)
    }

    @Test
    fun requestJwtUsesSelectedOrExplicitActiveKeyAndPreservesClaims() = runTest {
        val now = System.currentTimeMillis() / 1000L
        val claims = buildJsonObject {
            put("iss", identifier)
            put("sub", "subject")
            put("aud", "https://registrar.example")
            put("iat", now)
            put("exp", now + 3600L)
            put("jti", "caller-jti")
            put("extension", buildJsonObject { put("verbatim", true) })
        }
        val types = listOf("enrollment-application+jwt", "enrollment-status-request+jwt",
            "enrollment-change-response+jwt", "enrollment-key-update+jwt",
            "enrollment-withdrawal+jwt", "enrollment-invitation-redemption+jwt")
        for (typ in types) {
            val signed = signRequest().execute(SignAccountRequestJwtArgs(accountId, typ, claims))
            assertTrue(signed.isOk)
            verify(signed.value)
            val (header, payload) = parts(signed.value)
            assertEquals(typ, header["typ"]!!.jsonPrimitive.content)
            assertEquals(signingKey.kid, header["kid"]!!.jsonPrimitive.content)
            assertEquals(signingKey.alg, header["alg"]!!.jsonPrimitive.content)
            assertEquals(claims, payload)
        }
        val explicit = key()
        val signed = signRequest().execute(SignAccountRequestJwtArgs(accountId, types.first(), claims, explicit.kid))
        assertTrue(signed.isOk)
        verify(signed.value, explicit)
        assertEquals(explicit.kid, parts(signed.value).first["kid"]!!.jsonPrimitive.content)
        assertEquals(claims, parts(signed.value).second)
        assertTrue(revoke().execute(RevokeKeyArgs(accountId, explicit.id, "superseded")).isOk)
        val denied = signRequest().execute(SignAccountRequestJwtArgs(accountId, types.first(), claims, explicit.kid))
        assertTrue(denied.isErr)
        assertEquals("invalid_request", denied.error.code)
        val retired = key()
        assertTrue(retire().execute(RetireKeyArgs(accountId, retired.id)).isOk)
        assertTrue(signRequest().execute(SignAccountRequestJwtArgs(accountId, types.first(), claims, retired.kid)).isErr)
        val otherAccount = accountQueries.create("other-" + UUID.randomUUID(), "https://other.example").executeAsOne().id
        val otherKey = key(otherAccount)
        assertTrue(signRequest().execute(SignAccountRequestJwtArgs(accountId, types.first(), claims, otherKey.kid)).isErr)
        for (typ in listOf("entity-statement+jwt", "trust-mark+jwt", "trust-mark-delegation+jwt",
            "resolve-response+jwt", "jwk-set+jwt", "trust-mark-status-response+jwt",
            "explicit-registration-response+jwt", "entity-events-statement+jwt", "Bad+jwt", "enrollment", "+jwt")) {
            val rejected = signRequest().execute(SignAccountRequestJwtArgs(accountId, typ, claims))
            assertTrue(rejected.isErr, typ)
            assertEquals("invalid_request", rejected.error.code, typ)
        }
    }

    @Test
    fun retiredAndExpiredKeysCannotBeSelectedOrResolvedForSigning() = runTest {
        val old = key()
        assertTrue(retire().execute(RetireKeyArgs(accountId, old.id)).isOk)
        val rejected = SetAccountSigningKeySelectionCommandImpl(execution, accountQueries, jwkQueries, selectionQueries)
            .execute(SetAccountSigningKeySelectionArgs(accountId, identifier, 1L, old.id))
        assertTrue(rejected.isErr)
        assertEquals(signingKey.id, selectionQueries.findByAccountId(accountId).executeAsOne().jwk_id)
        source.connection.use { connection ->
            connection.prepareStatement("UPDATE Jwk SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?::uuid")
                .use { statement -> statement.setString(1, signingKey.id); statement.executeUpdate() }
        }
        assertTrue(resolveSigning().execute(ResolveAccountSigningKeyArgs(accountId, identifier)).isErr)
        assertTrue(signRequest().execute(SignAccountRequestJwtArgs(accountId, "enrollment-application+jwt", JsonObject(emptyMap()))).isErr)
    }

    @Test
    fun migrationTwentyThreeBackfillsExpiryAndOnlyRegisteredReasonTokens() = runTest {
        val upgradeSchema = createSchema()
        val upgradeSource = dataSource(upgradeSchema)
        val upgradeDriver = upgradeSource.asJdbcDriver()
        try {
            // SQLDelight applies migration n when upgrading schema version n to n + 1.
            val baseline = 23L
            Database.Schema.migrate(upgradeDriver, 0L, baseline)
            val owner = UUID.randomUUID().toString()
            val compromised = UUID.randomUUID().toString()
            val described = UUID.randomUUID().toString()
            upgradeSource.connection.use { connection ->
                connection.prepareStatement("INSERT INTO Account (id, username, identifier) VALUES (?::uuid, ?, ?)").use {
                    it.setString(1, owner); it.setString(2, "upgrade-" + UUID.randomUUID()); it.setString(3, identifier)
                    it.executeUpdate()
                }
                connection.prepareStatement("""
                    INSERT INTO Jwk (id, account_id, alg, kid, kms, kms_key_ref, key, revoked_at, revoked_reason)
                    VALUES (?::uuid, ?::uuid, 'ES256', ?, 'memory', ?, ?, TIMESTAMP '2025-01-01 12:00:00', ?)
                """.trimIndent()).use { statement ->
                    for ((id, reason) in listOf(compromised to "Compromised ", described to "rotated by ops")) {
                        statement.setString(1, id); statement.setString(2, owner); statement.setString(3, id)
                        statement.setString(4, id); statement.setString(5, signingKey.key); statement.setString(6, reason)
                        statement.executeUpdate()
                    }
                }
            }
            Database.Schema.migrate(upgradeDriver, baseline, 24L)
            val upgraded = JwkQueries(upgradeDriver, Jwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter))
            val registered = upgraded.findById(compromised).executeAsOne()
            val freeText = upgraded.findById(described).executeAsOne()
            assertEquals("compromised", registered.revocation_reason)
            assertNull(freeText.revocation_reason)
            assertEquals("Compromised ", registered.revoked_reason)
            assertEquals("rotated by ops", freeText.revoked_reason)
            assertEquals(registered.revoked_at, registered.expires_at)
            assertEquals(freeText.revoked_at, freeText.expires_at)
            assertEquals(2, upgraded.findHistoricalByAccountId(owner).executeAsList().size)
        } finally {
            try { upgradeDriver.close() } finally { dropSchema(upgradeSchema) }
        }
    }

    @Test
    fun reasonTypeAcceptsRegisteredAndFederationDefinedTokensOnly() {
        assertEquals(FederationKeyRevocationReason.COMPROMISED, FederationKeyRevocationReason.parse("compromised"))
        assertEquals(FederationKeyRevocationReason.SUPERSEDED, FederationKeyRevocationReason.parse("superseded"))
        assertEquals(FederationKeyRevocationReason.UNSPECIFIED, FederationKeyRevocationReason.parse("unspecified"))
        val custom = requireNotNull(FederationKeyRevocationReason.parse("federation_reason_1"))
        assertEquals("\"federation_reason_1\"", Json.encodeToString(custom))
        for (invalid in listOf("", "Key Compromised", "compromised ", "A", "_a", "a-b", "a".repeat(65))) {
            assertNull(FederationKeyRevocationReason.parse(invalid), invalid)
        }
        assertNotNull(FederationKeyRevocationReason.parse("a".repeat(64)))
    }
}
