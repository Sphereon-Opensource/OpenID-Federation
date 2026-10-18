package com.sphereon.openid.fed.services.command.jwk

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.core.api.Err
import com.sphereon.core.api.Ok
import com.sphereon.core.api.conf.TenantConfigService
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.json.jsonSerializer
import com.sphereon.core.api.session.asCoreApiServiceGraph
import com.sphereon.crypto.core.CoseJoseKeyMappingService
import com.sphereon.crypto.core.ManagedKeyInfo
import com.sphereon.crypto.core.ManagedKeyReference
import com.sphereon.crypto.core.ResolvedKeyInfo
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.jose.JwaCurve
import com.sphereon.crypto.core.kms.KeyManagerService
import com.sphereon.crypto.core.kms.asKeyManagerServiceGraph
import com.sphereon.crypto.core.kms.command.GenerateKeyResult
import com.sphereon.crypto.core.kms.command.GetKeyResult
import com.sphereon.crypto.jose.jws.JwsCompact
import com.sphereon.crypto.jose.jws.JwtServiceImpl
import com.sphereon.crypto.jose.jws.command.VerifyJwsArgs
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderConfig
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderFactoryImpl
import com.sphereon.di.context.PrincipalType
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.persistence.Database
import com.sphereon.openid.fed.persistence.database.JavaUuidStringAdapter
import com.sphereon.openid.fed.persistence.models.Account
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.Jwk
import com.sphereon.openid.fed.persistence.models.JwkQueries
import com.sphereon.openid.fed.services.CreateKeyArgs
import com.sphereon.openid.fed.services.command.resolution.createExactSuperiorEvidenceTestGraph
import com.sphereon.openid.fed.services.signPayload
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real PostgreSQL and P-256 KMS; the RPC boundary is an actual serialization round trip. */
class CreateKeyRoutedReceiptTest {
    companion object {
        private lateinit var postgres: PostgreSQLContainer<Nothing>
        @JvmStatic @BeforeClass fun start() {
            postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                withDatabaseName("oidfed_receipt"); withUsername("test"); withPassword("test"); start()
            }
        }
        @JvmStatic @AfterClass fun stop() { if (::postgres.isInitialized) postgres.stop() }
    }

    @Test fun serializedReceiptPersistsPublicKeyAndSignsWithTheSameManagedReference() = runBlocking {
        fixture { f ->
            val result = f.command().execute(f.args())
            assertTrue(result.isOk, result.toString())
            val row = f.rows.single()
            val ref = assertNotNull(f.originalReference)
            assertEquals(ref.alias, row.kms_key_ref)
            assertEquals(ref.providerId, row.kms)
            assertEquals(ref.kid, row.kid)
            assertEquals("ES256", row.alg)
            assertEquals(f.accountId, row.account_id)
            assertNull(f.wireResult!!.keyPair)
            val publicJwk = Json.parseToJsonElement(row.key).jsonObject
            assertEquals("P-256", publicJwk["crv"]?.toString()?.trim('"'))
            assertFalse(publicJwk.keys.any { it in setOf("d", "p", "q", "dp", "dq", "qi", "oth", "k") })
            val payload = buildJsonObject { put("purpose", "managed-reference-proof") }
            val kid = assertNotNull(row.kid)
            val algorithm = assertNotNull(row.alg)
            val signed = f.jwt.signPayload(payload, JwtHeader(typ = "enrollment-application+jwt", kid = kid, alg = algorithm),
                kid, row.kms_key_ref, row.kms)
            assertTrue(signed.isOk, signed.toString())
            val trusted = buildJsonObject { put("keys", JsonArray(listOf(publicJwk))) }
            val verified = f.jwt.verifyJws(VerifyJwsArgs(JwsCompact(signed.value), trustedJwks = trusted))
            assertTrue(verified.isOk && verified.value.isValid)
            val parts = signed.value.split('.').toMutableList()
            parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"purpose\":\"tampered\"}".toByteArray())
            val tampered = f.jwt.verifyJws(VerifyJwsArgs(JwsCompact(parts.joinToString(".")), trustedJwks = trusted))
            assertTrue(tampered.isErr || !tampered.value.isValid)
            f.exactlyOneGenerationAndResolution()
        }
    }

    @Test fun theSupportedStandaloneCommandApiAlsoCreatesAPublicFederationKey() = runBlocking {
        fixture { f ->
            val result = f.command(f.actualManager).execute(f.args())
            assertTrue(result.isOk, result.toString())
            assertEquals(1, f.rows.size)
            assertEquals(f.alias, f.rows.single().kms_key_ref)
            assertEquals(f.providerId, f.rows.single().kms)
            assertFalse(Json.parseToJsonElement(f.rows.single().key).jsonObject.containsKey("d"))
        }
    }

    @Test fun missingOrMismatchedReceiptRefusesWithoutPublicLookupOrRegeneration() = runBlocking {
        for (kind in listOf("missing", "alias", "provider", "kid", "algorithm")) fixture { f ->
            f.receiptKind = kind
            assertTrue(f.command().execute(f.args()).isErr, kind)
            assertEquals(1, f.generationCalls, kind)
            assertEquals(1, f.resultApiCalls, kind)
            assertEquals(0, f.legacyApiCalls, kind)
            assertEquals(0, f.resolutionCalls, kind)
            assertEquals(emptyList(), f.rows, kind)
        }
    }

    @Test fun resolvedPublicMaterialMustMatchReceiptCoordinatesAndAlgorithm() = runBlocking {
        for (kind in listOf("alias", "provider", "kid", "algorithm", "curve")) fixture { f ->
            f.publicKind = kind
            assertTrue(f.command().execute(f.args()).isErr, kind)
            f.exactlyOneGenerationAndResolution()
            assertEquals(emptyList(), f.rows, kind)
        }
    }

    @Test fun failedGenerationNeverRetriesThroughLegacyConvenienceApi() = runBlocking {
        fixture { f ->
            f.failGeneration = true
            assertTrue(f.command().execute(f.args()).isErr)
            assertEquals(1, f.resultApiCalls)
            assertEquals(0, f.legacyApiCalls)
            assertEquals(0, f.resolutionCalls)
            assertEquals(emptyList(), f.rows)
        }
    }

    @Test fun unresolvedPublicKeyDoesNotPersistOrRegenerate() = runBlocking {
        fixture { f ->
            f.publicKind = "missing"
            assertTrue(f.command().execute(f.args()).isErr)
            f.exactlyOneGenerationAndResolution()
            assertEquals(emptyList(), f.rows)
        }
    }

    private suspend fun fixture(test: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { test(f) } finally { f.close() }
    }

    private class Fixture {
        private val schema = "receipt_" + UUID.randomUUID().toString().replace("-", "")
        private val driver: app.cash.sqldelight.db.SqlDriver
        private val app = createExactSuperiorEvidenceTestGraph(this)
        private val session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "receipt-" + UUID.randomUUID(), principalType = PrincipalType.ANONYMOUS)
        private val execution = session.asCoreApiServiceGraph().serviceExecution
        val actualManager = session.graph.asKeyManagerServiceGraph().keyManagerService
        val jwt = (session.graph as JwtServiceImpl.Graph).jwtService
        val providerId = "receipt-provider-" + UUID.randomUUID()
        val alias = "receipt-key-" + UUID.randomUUID()
        val queries: JwkQueries
        val accountId: String
        val rows get() = queries.findAllByAccountId(accountId).executeAsList()
        var generationCalls = 0
        var resultApiCalls = 0
        var legacyApiCalls = 0
        var resolutionCalls = 0
        var receiptKind = "valid"
        var publicKind = "valid"
        var failGeneration = false
        var originalReference: ManagedKeyReference? = null
        var wireResult: GenerateKeyResult? = null
        private val manager = mockk<KeyManagerService>()

        init {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            }
            val separator = if ('?' in postgres.jdbcUrl) '&' else '?'
            driver = PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl + separator + "currentSchema=" + schema); user = postgres.username; password = postgres.password
            }.asJdbcDriver()
            Database.Schema.migrate(driver, 0L, Database.Schema.version)
            val accounts = AccountQueries(driver, Account.Adapter(JavaUuidStringAdapter))
            accountId = accounts.create("receipt-" + UUID.randomUUID(), "https://receipt.example/" + UUID.randomUUID()).executeAsOne().id
            queries = JwkQueries(driver, Jwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter))
            runBlocking {
                val provider = (app as SoftwareKmsProviderFactoryImpl.Graph).softwareKmsProvider.create(SoftwareKmsProviderConfig(id = providerId), execution)
                actualManager.registerProvider(provider, makeDefaultKms = true)
            }
            coEvery { manager.generateKey(any(), any(), any(), any(), any(), any()) } coAnswers {
                legacyApiCalls++
                val result = generateWire()
                result.keyPair ?: error("Generated key pair is null")
            }
            coEvery { manager.generateKeyResult(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                resultApiCalls++
                if (failGeneration) Err(IdkError.NOT_FOUND_ERROR(resource = "managed resource", message = "Managed generation refused"))
                else Ok(generateWire())
            }
            coEvery { manager.getKeyResult(any()) } coAnswers {
                resolutionCalls++
                assertEquals(originalReference, firstArg<ManagedKeyReference>())
                if (publicKind == "missing") Ok(publicWire(GetKeyResult())) else {
                    val original = actualManager.getKeyResult(assertNotNull(originalReference)).value.key!!
                    val public = CoseJoseKeyMappingService.toResolvedJwkKeyInfo(original.toManagedPublicKeyInfo())
                    Ok(publicWire(GetKeyResult(ManagedKeyInfo(
                        alias = if (publicKind == "alias") "other-alias" else original.alias,
                        providerId = if (publicKind == "provider") "other-provider" else original.providerId,
                        resolvedKeyInfo = ResolvedKeyInfo(
                            key = public.key.copy(
                                kid = if (publicKind == "kid") "other-kid" else public.key.kid,
                                crv = if (publicKind == "curve") JwaCurve.P_384 else public.key.crv,
                            ),
                            kid = if (publicKind == "kid") "other-kid" else public.kid,
                            signatureAlgorithm = if (publicKind == "algorithm") SignatureAlgorithm.ECDSA_SHA384 else public.signatureAlgorithm,
                            keyVisibility = com.sphereon.crypto.core.KeyVisibility.PUBLIC,
                        ),
                    ))))
                }
            }
        }

        private fun publicWire(result: GetKeyResult): GetKeyResult {
            val encoded = jsonSerializer.encodeToString(GetKeyResult.serializer(), result)
            val received = jsonSerializer.decodeFromString(GetKeyResult.serializer(), encoded)
            val before = result.key
            val after = received.key
            assertEquals(before?.alias, after?.alias)
            assertEquals(before?.providerId, after?.providerId)
            assertEquals(before?.kid, after?.kid)
            if (before != null && after != null) {
                val publicBefore = CoseJoseKeyMappingService.toResolvedJwkKeyInfo(before).key
                val publicAfter = CoseJoseKeyMappingService.toResolvedJwkKeyInfo(after).key
                assertEquals(publicBefore.toJsonString(), publicAfter.toJsonString())
                assertFalse(Json.parseToJsonElement(publicAfter.toJsonString()).jsonObject.keys.any {
                    it in setOf("d", "p", "q", "dp", "dq", "qi", "oth", "k")
                })
            }
            return received
        }

        private suspend fun generateWire(): GenerateKeyResult {
            generationCalls++
            val generated = actualManager.generateKeyResult(providerId, alias, alg = SignatureAlgorithm.ECDSA_SHA256)
            assertTrue(generated.isOk, generated.toString())
            originalReference = assertNotNull(generated.value.keyReference)
            assertNotNull(generated.value.keyPair)
            val wire = jsonSerializer.encodeToString(GenerateKeyResult.serializer(), generated.value)
            assertFalse(wire.contains("keyPair"))
            val received = jsonSerializer.decodeFromString(GenerateKeyResult.serializer(), wire)
            assertNull(received.keyPair)
            val ref = assertNotNull(received.keyReference)
            return received.copy(keyReference = when (receiptKind) {
                "missing" -> null
                "alias" -> ref.copy(alias = "other-alias")
                "provider" -> ref.copy(providerId = "other-provider")
                "kid" -> ref.copy(kid = "")
                "algorithm" -> ref.copy(signatureAlgorithm = SignatureAlgorithm.ECDSA_SHA384)
                else -> ref
            }).also { wireResult = it }
        }

        fun args() = CreateKeyCommandArgs(accountId, CreateKeyArgs(providerId = providerId, alias = alias))
        fun command(kms: KeyManagerService = manager): CreateKeyCommandImpl {
            val getKeys = mockk<GetKeysCommand>()
            coEvery { getKeys.execute(any()) } answers { Ok(emptyArray()) }
            return CreateKeyCommandImpl(execution, kms, getKeys, mockk<OidfConfigBinder>(), mockk<TenantConfigService>(), queries)
        }
        fun exactlyOneGenerationAndResolution() {
            assertEquals(1, generationCalls)
            assertEquals(1, resultApiCalls)
            assertEquals(0, legacyApiCalls)
            assertEquals(1, resolutionCalls)
        }
        fun close() {
            session.destroy()
            driver.close()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
