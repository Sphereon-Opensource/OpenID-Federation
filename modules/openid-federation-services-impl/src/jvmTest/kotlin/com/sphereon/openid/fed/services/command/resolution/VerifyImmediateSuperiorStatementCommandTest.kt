package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.asCoreApiServiceGraph
import com.sphereon.crypto.core.KeyVisibility
import com.sphereon.crypto.core.ManagedKeyInfoType
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.jose.Jwk as CryptoJwk
import com.sphereon.crypto.core.json.cryptoJsonSerializer
import com.sphereon.crypto.core.kms.asKeyManagerServiceGraph
import com.sphereon.crypto.jose.jws.JwsIdentifierMode
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.crypto.jose.jws.JwtServiceImpl
import com.sphereon.crypto.jose.jws.command.CreateJwsArgs
import com.sphereon.crypto.jose.jws.command.CreateJwsOpts
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderConfig
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderFactoryImpl
import com.sphereon.crypto.resolution.IdentifierContext
import com.sphereon.crypto.resolution.managed.ManagedOptsKeyInfo
import com.sphereon.di.context.PrincipalType
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommandImpl
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.core.cache.OidfCache
import com.sphereon.openid.fed.httpResolver.HttpMetadataCacheSerializers
import com.sphereon.openid.fed.httpResolver.HttpResolver
import com.sphereon.openid.fed.openapi.models.Jwk
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Real software-KMS signatures and the production verifier; HTTP is only an observed external boundary. */
class VerifyImmediateSuperiorStatementCommandTest {
    private val subjectId = "https://leaf.example"
    private val intermediateId = "https://intermediate.example"
    private val anchorId = "https://anchor.example"
    private val json = Json { ignoreUnknownKeys = true }
    private val leafMetadata = Json.parseToJsonElement(
        """{"federation_entity":{},"openid_provider":{"issuer":"https://leaf.example"}}"""
    ).jsonObject

    private lateinit var jwtService: JwtService
    private lateinit var keyManager: com.sphereon.crypto.core.kms.KeyManagerService
    private lateinit var execution: SessionExecution
    private var httpClient: HttpClient? = null
    private var networkRequests = 0
    private var baseNow = 0L

    @BeforeTest
    fun setUp() {
        val app = createExactSuperiorEvidenceTestGraph(this)
        val context = app.userContextManager.getAnonymous()
        val session = context.sessionContextManager.createOrGetFromId(
            "exact-superior-${hashCode()}", principalType = PrincipalType.ANONYMOUS
        )
        val provider = (app as SoftwareKmsProviderFactoryImpl.Graph).softwareKmsProvider.create(
            SoftwareKmsProviderConfig(id = "exact-superior-provider-${hashCode()}"),
            session.asCoreApiServiceGraph().serviceExecution,
        )
        keyManager = session.graph.asKeyManagerServiceGraph().keyManagerService
        keyManager.registerProvider(provider, makeDefaultKms = true)
        jwtService = (session.graph as JwtServiceImpl.Graph).jwtService
        execution = session.asCoreApiServiceGraph().serviceExecution
        httpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    networkRequests++
                    respond(content = "unexpected discovery", status = HttpStatusCode.NotFound)
                }
            }
        }
        baseNow = getCurrentEpochTimeSeconds()
    }

    @AfterTest
    fun tearDown() {
        httpClient?.close()
    }

    private data class Key(val signing: ManagedKeyInfoType<*>, val publicJson: JsonElement, val pin: Jwk)

    private data class SignedChain(
        val chain: List<String>,
        val pin: Jwk,
        val leafKey: Key,
        val superiorKey: Key,
        val superiorId: String,
        val minimumExpiry: Long,
    ) {
        val subjectEcJwt get() = chain[0]
        val uploadedSuperiorJwt get() = chain[1]
    }

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
        expiry: Long,
        metadata: JsonObject? = null,
        metadataPolicy: JsonObject? = null,
        authorityHint: String? = null,
    ): String {
        val claims = buildJsonObject {
            put("iss", issuer)
            put("sub", subject)
            put("iat", baseNow - 100)
            put("exp", expiry)
            put("jwks", buildJsonObject { put("keys", JsonArray(listOf(subjectKey.publicJson))) })
            metadata?.let { put("metadata", it) }
            metadataPolicy?.let { put("metadata_policy", it) }
            authorityHint?.let { put("authority_hints", JsonArray(listOf(JsonPrimitive(it)))) }
        }
        val header = buildJsonObject {
            put("typ", "entity-statement+jwt")
            put("kid", signer.signing.kid ?: error("generated key has no kid"))
        }
        val signed = jwtService.createJwsCompact(
            CreateJwsArgs(
                issuer = ManagedOptsKeyInfo(
                    identifier = signer.signing,
                    context = IdentifierContext(
                        clientId = "exact-superior-test", clientIdScheme = "jwt_vc_json", issuer = issuer
                    ),
                ),
                payload = claims,
                mode = JwsIdentifierMode.KID,
                opts = CreateJwsOpts(noIssPayloadUpdate = true, protectedHeader = header),
            )
        )
        assertTrue(signed.isOk, "real JWS signing failed: ${if (signed.isErr) signed.error else ""}")
        return signed.value.jwt
    }

    private suspend fun direct(
        fullAnchorEc: Boolean = false,
        leafExpiry: Long = baseNow + 7_200,
        superiorExpiry: Long = baseNow + 3_600,
        anchorExpiry: Long = baseNow + 10_800,
        metadataPolicy: JsonObject? = null,
        authorityHint: String? = anchorId,
    ): SignedChain {
        val leaf = key()
        val anchor = key()
        val chain = mutableListOf(
            statement(subjectId, subjectId, leaf, leaf, leafExpiry, metadata = leafMetadata, authorityHint = authorityHint),
            statement(anchorId, subjectId, anchor, leaf, superiorExpiry, metadataPolicy = metadataPolicy),
        )
        if (fullAnchorEc) chain += statement(anchorId, anchorId, anchor, anchor, anchorExpiry)
        val minimumExpiry = if (fullAnchorEc) minOf(leafExpiry, superiorExpiry, anchorExpiry)
            else minOf(leafExpiry, superiorExpiry)
        return SignedChain(chain, anchor.pin, leaf, anchor, anchorId, minimumExpiry)
    }

    private suspend fun intermediate(fullAnchorEc: Boolean = false): SignedChain {
        val leaf = key()
        val superior = key()
        val anchor = key()
        val chain = mutableListOf(
            statement(subjectId, subjectId, leaf, leaf, baseNow + 7_200, metadata = leafMetadata, authorityHint = intermediateId),
            statement(intermediateId, subjectId, superior, leaf, baseNow + 5_400),
            statement(anchorId, intermediateId, anchor, superior, baseNow + 3_600),
        )
        if (fullAnchorEc) chain += statement(anchorId, anchorId, anchor, anchor, baseNow + 10_800)
        return SignedChain(chain, anchor.pin, leaf, superior, intermediateId, baseNow + 3_600)
    }

    private fun verifier(contextPins: Map<String, List<Jwk>> = emptyMap()): VerifyTrustChainCommandImpl {
        val cache = OidfCache.createStringKeyCache(
            manager = OidfCache.newManager(),
            requirements = CacheRequirements(namespace = "exact-superior-evidence-test"),
            valueSerializer = HttpMetadataCacheSerializers.stringValue,
        )
        val resolver = HttpResolver(
            httpClient = requireNotNull(httpClient),
            cache = cache,
            responseMapper = { response -> response.bodyAsText() },
        )
        return VerifyTrustChainCommandImpl(
            execution,
            FederationContext(jwtService = jwtService, httpResolver = resolver, trustAnchorPublicKeys = contextPins),
        )
    }

    private fun args(
        signed: SignedChain,
        chain: List<String> = signed.chain,
        subjectEcJwt: String = signed.subjectEcJwt,
        uploadedSuperiorJwt: String = signed.uploadedSuperiorJwt,
        expectedSubjectEntityId: String = subjectId,
        expectedSuperiorEntityId: String = signed.superiorId,
        trustAnchorEntityId: String = anchorId,
        pins: List<Jwk> = listOf(signed.pin),
    ) = VerifyImmediateSuperiorStatementArgs(
        subjectEcJwt = subjectEcJwt,
        uploadedSuperiorJwt = uploadedSuperiorJwt,
        trustChain = chain,
        expectedSubjectEntityId = expectedSubjectEntityId,
        expectedSuperiorEntityId = expectedSuperiorEntityId,
        trustAnchorEntityId = trustAnchorEntityId,
        trustAnchorPublicKeys = pins,
    )

    private fun command(contextPins: Map<String, List<Jwk>> = emptyMap()) =
        VerifyImmediateSuperiorStatementCommandImpl(execution, verifier(contextPins))

    @Test
    fun realSignedDirectChainVerifiesWithExplicitAnchorPin() = runTest {
        val signed = direct(fullAnchorEc = false)
        val result = verifier().verifyTrustChain(signed.chain.toTypedArray(), anchorId, baseNow, listOf(signed.pin))

        assertTrue(result.isOk, "real signed chain must verify: ${if (result.isErr) result.error else ""}")
        assertTrue(result.value.isValid)
        assertEquals(0, networkRequests)
    }

    @Test
    fun directProofBindsExactTwoElementAndFullAnchorChains() = runTest {
        for (fullAnchorEc in listOf(false, true)) {
            val signed = direct(fullAnchorEc = fullAnchorEc)
            val before = getCurrentEpochTimeSeconds()
            val result = command().execute(args(signed))
            val after = getCurrentEpochTimeSeconds()

            assertTrue(result.isOk, "direct signed relationship must verify: ${if (result.isErr) result.error else ""}")
            val evidence = result.value
            assertEquals(subjectId, evidence.subjectEntityId)
            assertEquals(anchorId, evidence.superiorEntityId)
            assertEquals(anchorId, evidence.trustAnchorEntityId)
            assertEquals(signed.chain, evidence.trustChain)
            assertEquals(leafMetadata, evidence.effectiveMetadata)
            assertEquals(signed.minimumExpiry, evidence.validUntilEpochSeconds)
            assertTrue(evidence.validatedAtEpochSeconds in before..after)
            assertEquals("CORE_FEDERATION_1_1_RELATIONSHIP_AND_POLICY", evidence.scope.toString())
        }
        assertEquals(0, networkRequests)
    }

    @Test
    fun intermediateProofBindsImmediateSuperiorWithOrWithoutAnchorEc() = runTest {
        for (fullAnchorEc in listOf(false, true)) {
            val signed = intermediate(fullAnchorEc)
            val result = command().execute(args(signed))

            assertTrue(result.isOk, "intermediate signed relationship must verify: ${if (result.isErr) result.error else ""}")
            val evidence = result.value
            assertEquals(subjectId, evidence.subjectEntityId)
            assertEquals(intermediateId, evidence.superiorEntityId)
            assertEquals(anchorId, evidence.trustAnchorEntityId)
            assertEquals(signed.chain, evidence.trustChain)
            assertEquals(signed.minimumExpiry, evidence.validUntilEpochSeconds)
        }
        assertEquals(0, networkRequests)
    }

    @Test
    fun validResignedSubjectAndSuperiorCannotReplaceUploadedBytes() = runTest {
        val signed = direct()
        val alternateLeaf = statement(
            subjectId, subjectId, signed.leafKey, signed.leafKey, baseNow + 6_000,
            metadata = leafMetadata, authorityHint = anchorId,
        )
        val alternateSuperior = statement(
            anchorId, subjectId, signed.superiorKey, signed.leafKey, baseNow + 2_000,
        )
        assertNotEquals(signed.subjectEcJwt, alternateLeaf)
        assertNotEquals(signed.uploadedSuperiorJwt, alternateSuperior)
        for (replacement in listOf(
            listOf(alternateLeaf, signed.uploadedSuperiorJwt),
            listOf(signed.subjectEcJwt, alternateSuperior),
        )) {
            val verified = verifier().verifyTrustChain(replacement.toTypedArray(), anchorId, baseNow, listOf(signed.pin))
            assertTrue(verified.isOk, "substitute is genuinely signed: ${if (verified.isErr) verified.error else ""}")
            assertTrue(verified.value.isValid)
            assertTrue(command().execute(args(signed, chain = replacement)).isErr, "exact uploaded compact bytes must be bound")
        }
    }

    @Test
    fun wrongExpectedSubjectSuperiorAndAnchorAreRejected() = runTest {
        val signed = direct()
        assertTrue(command().execute(args(signed, expectedSubjectEntityId = "https://other-leaf.example")).isErr)
        assertTrue(command().execute(args(signed, expectedSuperiorEntityId = "https://other-superior.example")).isErr)
        assertTrue(command().execute(args(signed, trustAnchorEntityId = "https://other-anchor.example")).isErr)
    }

    @Test
    fun selfIssuedSecondLinkSwappedLinksAndSingleAnchorPathCannotProveSuperior() = runTest {
        val signed = direct()
        val selfIssued = statement(anchorId, anchorId, signed.superiorKey, signed.superiorKey, baseNow + 3_600)
        assertTrue(command().execute(args(signed, chain = listOf(signed.subjectEcJwt, selfIssued), uploadedSuperiorJwt = selfIssued)).isErr)
        assertTrue(command().execute(args(signed, chain = signed.chain.reversed())).isErr)
        assertTrue(command().execute(args(signed, chain = listOf(signed.subjectEcJwt))).isErr)
        assertTrue(command().execute(args(direct(authorityHint = null))).isErr)
    }

    @Test
    fun explicitPinsAreRequiredEvenWithCorrectAmbientAnchorPins() = runTest {
        val signed = direct(fullAnchorEc = true)
        val result = command(mapOf(anchorId to listOf(signed.pin))).execute(args(signed, pins = emptyList()))

        assertTrue(result.isErr, "ambient verifier context must not replace explicit server-selected pins")
        assertEquals(0, networkRequests)
    }

    @Test
    fun unrelatedAndSameKidWrongPinsCannotAuthorizeSignedChain() = runTest {
        val signed = direct(fullAnchorEc = true)
        val unrelated = key()
        val anchorKid = signed.pin.kid ?: error("signed anchor has no kid")
        val sameKidWrongPin = json.decodeFromJsonElement<Jwk>(
            JsonObject(unrelated.publicJson.jsonObject + ("kid" to JsonPrimitive(anchorKid)))
        )

        assertTrue(command().execute(args(signed, pins = listOf(unrelated.pin))).isErr)
        assertEquals(anchorKid, sameKidWrongPin.kid)
        assertTrue(command().execute(args(signed, pins = listOf(sameKidWrongPin))).isErr)
        assertEquals(0, networkRequests)
    }

    @Test
    fun tamperedLeafAndSuperiorSignaturesAreRejectedByRealVerifier() = runTest {
        val signed = direct()
        for (position in listOf(0, 1)) {
            val tampered = signed.chain.toMutableList()
            tampered[position] = tamperSignature(tampered[position])
            val result = command().execute(
                args(signed, chain = tampered, subjectEcJwt = tampered[0], uploadedSuperiorJwt = tampered[1])
            )
            assertTrue(result.isErr, "tampered signed link $position must be rejected")
        }
    }

    @Test
    fun effectiveMetadataComesFromSignedSuperiorPolicy() = runTest {
        val policy = Json.parseToJsonElement(
            """{"openid_provider":{"organization_name":{"value":"Signed superior"}}}"""
        ).jsonObject
        val signed = direct(metadataPolicy = policy)
        val result = command().execute(args(signed))

        assertTrue(result.isOk, "signed policy must resolve: ${if (result.isErr) result.error else ""}")
        assertEquals(
            Json.parseToJsonElement(
                """{"federation_entity":{},"openid_provider":{"issuer":"https://leaf.example","organization_name":"Signed superior"}}"""
            ).jsonObject,
            result.value.effectiveMetadata,
        )
    }

    @Test
    fun earliestSignedExpiryAndAlreadyExpiredWithinSkewCannotIssueEvidence() = runTest {
        val leafMinimum = direct(leafExpiry = baseNow + 1_800, superiorExpiry = baseNow + 3_600)
        val leafResult = command().execute(args(leafMinimum))
        assertTrue(leafResult.isOk, "future-dated chain must verify: ${if (leafResult.isErr) leafResult.error else ""}")
        assertEquals(baseNow + 1_800, leafResult.value.validUntilEpochSeconds)

        val anchorMinimum = direct(fullAnchorEc = true, anchorExpiry = baseNow + 1_200)
        val anchorResult = command().execute(args(anchorMinimum))
        assertTrue(anchorResult.isOk, "included anchor EC must verify: ${if (anchorResult.isErr) anchorResult.error else ""}")
        assertEquals(baseNow + 1_200, anchorResult.value.validUntilEpochSeconds)

        val expired = direct(superiorExpiry = baseNow - 1)
        val verifierResult = verifier().verifyTrustChain(
            expired.chain.toTypedArray(), anchorId, baseNow, listOf(expired.pin)
        )
        assertTrue(verifierResult.isOk, "verifier skew should accept this signed boundary fixture")
        assertTrue(verifierResult.value.isValid)
        assertTrue(command().execute(args(expired)).isErr, "already-expired signed evidence has no positive lifetime")
    }

    private fun tamperSignature(compact: String): String {
        val components = compact.split('.')
        assertEquals(3, components.size)
        val signature = components[2]
        assertFalse(signature.isEmpty())
        val first = if (signature[0] == 'A') 'B' else 'A'
        return "${components[0]}.${components[1]}.$first${signature.drop(1)}"
    }
}
