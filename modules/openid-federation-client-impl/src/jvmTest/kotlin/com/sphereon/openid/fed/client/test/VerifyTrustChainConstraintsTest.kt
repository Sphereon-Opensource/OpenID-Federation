package com.sphereon.openid.fed.client.test

import com.sphereon.core.api.session.asCoreApiServiceGraph
import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.error.IdkError
import com.sphereon.crypto.core.KeyVisibility
import com.sphereon.crypto.core.ManagedKeyInfoType
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.jose.Jwk as CryptoJwk
import com.sphereon.crypto.core.json.cryptoJsonSerializer
import com.sphereon.crypto.core.kms.asKeyManagerServiceGraph
import com.sphereon.crypto.jose.jws.JwsIdentifierMode
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.crypto.jose.jws.JwtServiceImpl
import com.sphereon.crypto.jose.jws.JwsValidationResult
import com.sphereon.crypto.jose.jws.command.CreateJwsArgs
import com.sphereon.crypto.jose.jws.command.CreateJwsOpts
import com.sphereon.crypto.jose.jws.command.VerifyJwsArgs
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderConfig
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderFactoryImpl
import com.sphereon.crypto.resolution.IdentifierContext
import com.sphereon.crypto.resolution.managed.ManagedOptsKeyInfo
import com.sphereon.di.context.PrincipalType
import com.sphereon.openid.fed.client.FederationClient
import com.sphereon.openid.fed.client.asFederationClientGraph
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommandImpl
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.core.cache.OidfCache
import com.sphereon.openid.fed.core.error.TrustChainValidationFailedError
import com.sphereon.openid.fed.httpResolver.HttpMetadataCacheSerializers
import com.sphereon.openid.fed.httpResolver.HttpResolver
import com.sphereon.openid.fed.openapi.models.Jwk
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real software-KMS JWSs exercise the production verifier without live network or self-asserted signatures. */
class VerifyTrustChainConstraintsTest {
    private val now = 1_700_000_000L
    private val leafId = "https://leaf.example"
    private val intermediateId = "https://intermediate.example"
    private val anchorId = "https://anchor.example"
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var client: FederationClient
    private lateinit var jwtService: JwtService
    private lateinit var keyManager: com.sphereon.crypto.core.kms.KeyManagerService
    private lateinit var execution: SessionExecution
    private var mockHttpClient: HttpClient? = null
    private var anchorEcFetches = 0

    @BeforeTest
    fun setUp() {
        val app = createClientImplTestAppGraph(this)
        val context = app.userContextManager.getAnonymous()
        val session = context.sessionContextManager.createOrGetFromId(
            "constraint-test-${hashCode()}", principalType = PrincipalType.ANONYMOUS
        )
        val provider = (app as SoftwareKmsProviderFactoryImpl.Graph).softwareKmsProvider.create(
            SoftwareKmsProviderConfig(id = "constraint-test-provider-${hashCode()}"),
            session.asCoreApiServiceGraph().serviceExecution
        )
        keyManager = session.graph.asKeyManagerServiceGraph().keyManagerService
        keyManager.registerProvider(provider, makeDefaultKms = true)
        jwtService = (session.graph as JwtServiceImpl.Graph).jwtService
        client = session.asFederationClientGraph().federationClient
        execution = session.asCoreApiServiceGraph().serviceExecution
    }

    @AfterTest
    fun tearDown() {
        mockHttpClient?.close()
    }

    private data class Key(val signing: ManagedKeyInfoType<*>, val publicJson: JsonElement, val pin: Jwk)
    private data class SignedChain(val statements: Array<String>, val anchorPin: Jwk)

    private suspend fun key(): Key {
        val pair = keyManager.generateKeyAsync(alg = SignatureAlgorithm.ECDSA_SHA256)
        val signing: ManagedKeyInfoType<*> = pair.joseToManagedKeyInfo(KeyVisibility.PRIVATE)
        val publicJwk = pair.joseToManagedKeyInfo(KeyVisibility.PUBLIC).key as CryptoJwk
        val publicJson = cryptoJsonSerializer.encodeToJsonElement(CryptoJwk.serializer(), publicJwk)
        return Key(signing, publicJson, json.decodeFromJsonElement<Jwk>(publicJson))
    }

    private suspend fun statement(
        issuer: String,
        subject: String,
        signer: Key,
        subjectKey: Key,
        metadata: JsonObject? = null,
        authorityHint: String? = null,
        constraints: JsonElement? = null
    ): String {
        val claims = buildJsonObject {
            put("iss", issuer)
            put("sub", subject)
            put("iat", now - 100)
            put("exp", now + 3600)
            put("jwks", buildJsonObject { put("keys", JsonArray(listOf(subjectKey.publicJson))) })
            metadata?.let { put("metadata", it) }
            authorityHint?.let { put("authority_hints", JsonArray(listOf(JsonPrimitive(it)))) }
            constraints?.let { put("constraints", it) }
        }
        val header = buildJsonObject {
            put("typ", "entity-statement+jwt")
            put("kid", signer.signing.kid ?: error("generated key has no kid"))
        }
        val result = jwtService.createJwsCompact(
            CreateJwsArgs(
                issuer = ManagedOptsKeyInfo(
                    identifier = signer.signing,
                    context = IdentifierContext(clientId = "constraint-test", clientIdScheme = "jwt_vc_json", issuer = issuer)
                ),
                payload = claims,
                mode = JwsIdentifierMode.KID,
                opts = CreateJwsOpts(noIssPayloadUpdate = true, protectedHeader = header)
            )
        )
        assertTrue(result.isOk, "real JWS signing failed: ${if (result.isErr) result.error else ""}")
        return result.value.jwt
    }

    private suspend fun signedDirect(
        constraints: JsonElement? = null,
        fullAnchorEc: Boolean = false,
        metadata: JsonObject = mixedMetadata()
    ): SignedChain {
        val leaf = key()
        val anchor = key()
        val chain = mutableListOf(
            statement(leafId, leafId, leaf, leaf, metadata = metadata, authorityHint = anchorId),
            statement(anchorId, leafId, anchor, leaf, constraints = constraints)
        )
        if (fullAnchorEc) chain += statement(anchorId, anchorId, anchor, anchor)
        return SignedChain(chain.toTypedArray(), anchor.pin)
    }

    private suspend fun signedIntermediate(
        lowerConstraints: JsonElement? = null,
        upperConstraints: JsonElement? = null,
        fullAnchorEc: Boolean = false
    ): SignedChain {
        val leaf = key()
        val intermediate = key()
        val anchor = key()
        val chain = mutableListOf(
            statement(leafId, leafId, leaf, leaf, metadata = mixedMetadata(), authorityHint = intermediateId),
            statement(intermediateId, leafId, intermediate, leaf, constraints = lowerConstraints),
            statement(anchorId, intermediateId, anchor, intermediate, constraints = upperConstraints)
        )
        if (fullAnchorEc) chain += statement(anchorId, anchorId, anchor, anchor)
        return SignedChain(chain.toTypedArray(), anchor.pin)
    }

    private suspend fun signedAnchorOnly(): SignedChain {
        val anchor = key()
        return SignedChain(arrayOf(statement(anchorId, anchorId, anchor, anchor)), anchor.pin)
    }

    private fun verifierWithAnchorEcResponse(
        anchorEcJwt: String,
        contextPins: Map<String, List<Jwk>> = emptyMap(),
        verificationJwtService: JwtService = jwtService,
    ): VerifyTrustChainCommandImpl {
        mockHttpClient?.close()
        anchorEcFetches = 0
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    anchorEcFetches++
                    respond(content = anchorEcJwt, status = HttpStatusCode.OK)
                }
            }
        }
        mockHttpClient = httpClient
        val cache = OidfCache.createStringKeyCache(
            manager = OidfCache.newManager(),
            requirements = CacheRequirements(namespace = "verify-trust-anchor-pins-test"),
            valueSerializer = HttpMetadataCacheSerializers.stringValue
        )
        val resolver = HttpResolver(
            httpClient = httpClient,
            cache = cache,
            responseMapper = { response -> response.bodyAsText() }
        )
        val context = FederationContext(
            jwtService = verificationJwtService,
            httpResolver = resolver,
            trustAnchorPublicKeys = contextPins
        )
        return VerifyTrustChainCommandImpl(execution, context)
    }

    private fun constraint(value: String): JsonElement = Json.parseToJsonElement(value)

    private fun mixedMetadata(): JsonObject = constraint(
        """{"federation_entity":{},"openid_provider":{},"openid_credential_issuer":{}}"""
    ) as JsonObject

    private fun federationOnlyMetadata(): JsonObject = constraint("""{"federation_entity":{}}""") as JsonObject

    private suspend fun verify(chain: SignedChain) = client.trustChainVerify(
        trustChain = chain.statements,
        trustAnchor = anchorId,
        currentTime = now,
        trustAnchorPublicKeys = listOf(chain.anchorPin)
    )

    @Test
    fun directMixedRoleChainVerifiesWithAllowedTypeConstraint() = runTest {
        val result = verify(signedDirect(constraint("""{"allowed_entity_types":["openid_provider"]}""")))
        assertTrue(result.isOk, "valid signed mixed-role chain must not be rejected: ${if (result.isErr) result.error else ""}")
        assertTrue(result.value.isValid)
    }

    @Test
    fun intermediateMixedRoleChainVerifiesWithCumulativeConstraints() = runTest {
        val chain = signedIntermediate(
            lowerConstraints = constraint("""{"allowed_entity_types":["openid_provider","openid_credential_issuer"]}"""),
            upperConstraints = constraint("""{"allowed_entity_types":["openid_provider"]}""")
        )
        val result = verify(chain)
        assertTrue(result.isOk, "valid signed intermediate chain must not be rejected: ${if (result.isErr) result.error else ""}")
        assertTrue(result.value.isValid)
    }

    @Test
    fun emptyAllowedListVerifiesMixedRoleChainAndUnknownConstraintExtensionIsIgnored() = runTest {
        val empty = verify(signedDirect(constraint("""{"allowed_entity_types":[]}""")))
        assertTrue(empty.isOk, "empty allowed list filters roles, not the signed chain: ${if (empty.isErr) empty.error else ""}")
        val extension = verify(signedDirect(constraint("""{"unknown_constraint_extension":true}""")))
        assertTrue(extension.isOk, "unknown constraint extension should be ignored: ${if (extension.isErr) extension.error else ""}")
    }

    @Test
    fun explicitFederationEntityAllowedTypeIsInvalid() = runTest {
        val chain = signedDirect(
            constraints = constraint("""{"allowed_entity_types":["federation_entity"]}"""),
            metadata = federationOnlyMetadata()
        )
        assertTrue(verify(chain).isErr)
    }

    @Test
    fun malformedConstraintsFailClosed() = runTest {
        for (bad in listOf(
            """[]""",
            """{"allowed_entity_types":{"openid_provider":true}}""",
            """{"allowed_entity_types":["openid_provider",7]}""",
            """{"allowed_entity_types":null}""",
            """{"max_path_length":null}""",
            """{"max_path_length":"0"}""",
            """{"naming_constraints":null}""",
            """{"naming_constraints":{"permitted":[7]}}"""
        )) {
            val chain = signedDirect(constraint(bad), metadata = federationOnlyMetadata())
            assertTrue(verify(chain).isErr, "malformed signed constraint must fail: $bad")
        }
    }

    @Test
    fun directMaxPathLengthZeroIsValidWithOrWithoutAnchorEc() = runTest {
        for (fullAnchorEc in listOf(false, true)) {
            val result = verify(signedDirect(constraint("""{"max_path_length":0}"""), fullAnchorEc))
            assertTrue(result.isOk, "direct max_path_length=0 must be valid: ${if (result.isErr) result.error else ""}")
        }
    }

    @Test
    fun anchorMaxPathLengthZeroRejectsIntermediateWithOrWithoutAnchorEc() = runTest {
        for (fullAnchorEc in listOf(false, true)) {
            val chain = signedIntermediate(
                upperConstraints = constraint("""{"max_path_length":0}"""),
                fullAnchorEc = fullAnchorEc
            )
            assertTrue(verify(chain).isErr, "anchor max_path_length=0 cannot permit an intermediate")
        }
    }

    @Test
    fun negativeMaxPathLengthIsInvalid() = runTest {
        val chain = signedDirect(constraint("""{"max_path_length":-1}"""), metadata = federationOnlyMetadata())
        assertTrue(verify(chain).isErr)
    }

    @Test
    fun fullChainRejectsUnpinnedAnchorEvenWhenFetchedEcIsTheSameSignedJwt() = runTest {
        val chain = signedDirect(fullAnchorEc = true)
        val verifier = verifierWithAnchorEcResponse(chain.statements.last())

        val result = verifier.verifyTrustChain(chain.statements, anchorId, now, null)

        assertTrue(result.isErr, "a self-consistent fetched anchor EC is not a configured trust root")
        val error = result.error as? TrustChainValidationFailedError
        assertTrue(error?.reason?.contains("out-of-band", ignoreCase = true) == true, "$error")
        assertEquals(0, anchorEcFetches, "verification must reject missing pins before fetching anchor EC")
    }

    @Test
    fun anchorOnlyChainRejectsUnpinnedAnchorEvenWhenFetchedEcIsTheSameSignedJwt() = runTest {
        val chain = signedAnchorOnly()
        val verifier = verifierWithAnchorEcResponse(chain.statements.single())

        val result = verifier.verifyTrustChain(chain.statements, anchorId, now, null)

        assertTrue(result.isErr, "a self-consistent fetched anchor EC is not a configured trust root")
        val error = result.error as? TrustChainValidationFailedError
        assertTrue(error?.reason?.contains("out-of-band", ignoreCase = true) == true, "$error")
        assertEquals(0, anchorEcFetches, "verification must reject missing pins before fetching anchor EC")
    }

    @Test
    fun explicitAnchorPinsVerifyFullAndAnchorOnlyChainsWithoutRetrieval() = runTest {
        for (chain in listOf(signedDirect(fullAnchorEc = true), signedAnchorOnly())) {
            val verifier = verifierWithAnchorEcResponse(chain.statements.last())

            val result = verifier.verifyTrustChain(chain.statements, anchorId, now, listOf(chain.anchorPin))

            assertTrue(result.isOk, "configured anchor pin must verify: ${if (result.isErr) result.error else ""}")
            assertTrue(result.value.isValid)
            assertEquals(0, anchorEcFetches, "out-of-band pin verification must not retrieve anchor EC")
        }
    }

    @Test
    fun exactAnchorContextPinVerifiesWithoutArgumentPinsOrRetrieval() = runTest {
        val chain = signedDirect(fullAnchorEc = true)
        val verifier = verifierWithAnchorEcResponse(
            chain.statements.last(), contextPins = mapOf(anchorId to listOf(chain.anchorPin))
        )

        val result = verifier.verifyTrustChain(chain.statements, anchorId, now, null)

        assertTrue(result.isOk, "exact-anchor context pin must verify: ${if (result.isErr) result.error else ""}")
        assertTrue(result.value.isValid)
        assertEquals(0, anchorEcFetches)
    }

    @Test
    fun wrongPublicKeyWithSameKidFailsAnchorSignatureVerification() = runTest {
        val chain = signedDirect(fullAnchorEc = true)
        val unrelatedKey = key()
        val anchorKid = chain.anchorPin.kid ?: error("signed anchor has no kid")
        val sameKidWrongKey = json.decodeFromJsonElement<Jwk>(
            JsonObject(unrelatedKey.publicJson.jsonObject + ("kid" to JsonPrimitive(anchorKid)))
        )
        val verifier = verifierWithAnchorEcResponse(chain.statements.last())

        val result = verifier.verifyTrustChain(chain.statements, anchorId, now, listOf(sameKidWrongKey))

        assertEquals(anchorKid, sameKidWrongKey.kid)
        assertTrue(result.isErr, "a matching kid must not substitute for signature verification")
        val error = result.error as? TrustChainValidationFailedError
        assertTrue(error?.reason?.contains("signature", ignoreCase = true) == true, "$error")
        assertEquals(0, anchorEcFetches)
    }

    @Test
    fun unrelatedContextPinDoesNotAuthorizeAnchorAndOmittedEcStillRequiresPins() = runTest {
        val fullChain = signedDirect(fullAnchorEc = true)
        val unrelatedVerifier = verifierWithAnchorEcResponse(
            fullChain.statements.last(), contextPins = mapOf("https://unrelated.example" to listOf(fullChain.anchorPin))
        )
        val unrelatedResult = unrelatedVerifier.verifyTrustChain(fullChain.statements, anchorId, now, null)
        assertTrue(unrelatedResult.isErr, "a pin for another entity is not an anchor pin")
        assertEquals(0, anchorEcFetches)

        val omittedEcChain = signedDirect(fullAnchorEc = false)
        val omittedEcVerifier = verifierWithAnchorEcResponse(fullChain.statements.last())
        val omittedEcResult = omittedEcVerifier.verifyTrustChain(omittedEcChain.statements, anchorId, now, null)
        assertTrue(omittedEcResult.isErr, "omitting the anchor EC never removes the pin requirement")
        assertEquals(0, anchorEcFetches)
    }

    @Test
    fun cancellationFromJwsVerificationEscapesTheTrustChainCommand() = runTest {
        val chain = signedDirect(fullAnchorEc = true)
        val healthyVerifier = verifierWithAnchorEcResponse(chain.statements.last())
        val healthy = healthyVerifier.verifyTrustChain(chain.statements, anchorId, now, listOf(chain.anchorPin))
        assertTrue(healthy.isOk, "real signed-chain control failed: ${if (healthy.isErr) healthy.error else ""}")
        assertTrue(healthy.value.isValid)

        val cancellation = CancellationException("verification cancelled")
        val throwingService = object : JwtService by jwtService {
            override suspend fun verifyJws(args: VerifyJwsArgs): IdkResult<JwsValidationResult, IdkError> {
                throw cancellation
            }
        }
        val verifier = verifierWithAnchorEcResponse(
            chain.statements.last(), verificationJwtService = throwingService
        )

        val escaped = assertFailsWith<CancellationException> {
            verifier.verifyTrustChain(chain.statements, anchorId, now, listOf(chain.anchorPin))
        }
        assertEquals(cancellation::class, escaped::class)
        assertEquals(cancellation.message, escaped.message)
        assertTrue(
            escaped === cancellation || escaped.cause === cancellation,
            "injected cancellation escapes directly or as a coroutine recovery copy",
        )
        assertEquals(0, anchorEcFetches)
    }

    @Test
    fun ordinaryJwsVerificationExceptionReturnsControlledChainError() = runTest {
        val chain = signedDirect(fullAnchorEc = true)
        val healthyVerifier = verifierWithAnchorEcResponse(chain.statements.last())
        val healthy = healthyVerifier.verifyTrustChain(chain.statements, anchorId, now, listOf(chain.anchorPin))
        assertTrue(healthy.isOk, "real signed-chain control failed: ${if (healthy.isErr) healthy.error else ""}")
        assertTrue(healthy.value.isValid)

        val failure = IllegalStateException("verification dependency failed")
        val throwingService = object : JwtService by jwtService {
            override suspend fun verifyJws(args: VerifyJwsArgs): IdkResult<JwsValidationResult, IdkError> {
                throw failure
            }
        }
        val verifier = verifierWithAnchorEcResponse(
            chain.statements.last(), verificationJwtService = throwingService
        )

        val result = verifier.verifyTrustChain(chain.statements, anchorId, now, listOf(chain.anchorPin))
        assertTrue(result.isErr, "ordinary dependency failure must be a controlled chain error")
        val error = result.error as? TrustChainValidationFailedError
        assertTrue(error?.reason?.contains("verification dependency failed") == true, "$error")
        assertSame(failure, error?.exception)
        assertEquals(0, anchorEcFetches)
    }
}
