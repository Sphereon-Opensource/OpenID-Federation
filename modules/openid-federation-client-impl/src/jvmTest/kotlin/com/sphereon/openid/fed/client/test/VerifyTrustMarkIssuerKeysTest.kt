package com.sphereon.openid.fed.client.test

import com.sphereon.core.api.IdkResult
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
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkCommandImpl
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.core.cache.OidfCache
import com.sphereon.openid.fed.httpResolver.HttpMetadataCacheSerializers
import com.sphereon.openid.fed.httpResolver.HttpResolver
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.TrustChainResolveResponse
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * OpenID Federation 1.1 section 7.3: trust in the Trust Mark issuer comes first, and the mark's signature is checked
 * with that trusted issuer's key. For the Trust Anchor that is the Trust Anchor Entity Configuration the caller trusts;
 * for another issuer it is the issuer's Entity Configuration inside the verified Trust Chain. A separately fetched
 * Entity Configuration, which is only self-signed, must not supply the key.
 */
class VerifyTrustMarkIssuerKeysTest {
    private val anchorId = "https://anchor.example"
    private val issuerId = "https://issuer.example"
    private val subjectId = "https://leaf.example"
    private val markType = "https://anchor.example/marks/member"
    private val now = System.currentTimeMillis() / 1000
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var jwtService: JwtService
    private lateinit var keyManager: com.sphereon.crypto.core.kms.KeyManagerService
    private lateinit var execution: SessionExecution
    private var httpClient: HttpClient? = null

    private data class Key(val signing: ManagedKeyInfoType<*>, val publicJson: JsonElement)

    @BeforeTest
    fun setUp() {
        val app = createClientImplTestAppGraph(this)
        val session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "trust-mark-issuer-keys-${hashCode()}", principalType = PrincipalType.ANONYMOUS
        )
        val provider = (app as SoftwareKmsProviderFactoryImpl.Graph).softwareKmsProvider.create(
            SoftwareKmsProviderConfig(id = "trust-mark-issuer-keys-${hashCode()}"),
            session.asCoreApiServiceGraph().serviceExecution
        )
        keyManager = session.graph.asKeyManagerServiceGraph().keyManagerService
        keyManager.registerProvider(provider, makeDefaultKms = true)
        jwtService = (session.graph as JwtServiceImpl.Graph).jwtService
        execution = session.asCoreApiServiceGraph().serviceExecution
    }

    @AfterTest
    fun tearDown() {
        httpClient?.close()
    }

    private suspend fun key(): Key {
        val pair = keyManager.generateKeyAsync(alg = SignatureAlgorithm.ECDSA_SHA256)
        val publicJwk = pair.joseToManagedKeyInfo(KeyVisibility.PUBLIC).key as CryptoJwk
        return Key(pair.joseToManagedKeyInfo(KeyVisibility.PRIVATE),
            cryptoJsonSerializer.encodeToJsonElement(CryptoJwk.serializer(), publicJwk))
    }

    private suspend fun sign(signer: Key, typ: String, claims: JsonObject): String {
        val result = jwtService.createJwsCompact(
            CreateJwsArgs(
                issuer = ManagedOptsKeyInfo(
                    identifier = signer.signing,
                    context = IdentifierContext(clientId = "trust-mark-test", clientIdScheme = "jwt_vc_json", issuer = anchorId)
                ),
                payload = claims,
                mode = JwsIdentifierMode.KID,
                opts = CreateJwsOpts(noIssPayloadUpdate = true, protectedHeader = buildJsonObject {
                    put("typ", typ)
                    put("kid", signer.signing.kid ?: error("generated key has no kid"))
                })
            )
        )
        assertTrue(result.isOk, "signing failed: ${if (result.isErr) result.error else ""}")
        return result.value.jwt
    }

    private fun configurationClaims(entity: String, keys: List<Key>, extra: JsonObject = JsonObject(emptyMap())) = JsonObject(buildJsonObject {
        put("iss", entity)
        put("sub", entity)
        put("iat", now - 10)
        put("exp", now + 3600)
        put("jwks", buildJsonObject { put("keys", JsonArray(keys.map { it.publicJson })) })
        put("metadata", buildJsonObject { put("federation_entity", JsonObject(emptyMap())) })
    } + extra)

    private fun anchorConfiguration(keys: List<Key>, issuers: List<String>): EntityConfigurationStatement = json.decodeFromJsonElement(
        configurationClaims(anchorId, keys, buildJsonObject {
            put("trust_mark_issuers", buildJsonObject { put(markType, JsonArray(issuers.map { kotlinx.serialization.json.JsonPrimitive(it) })) })
        })
    )

    private suspend fun trustMark(issuer: String, signer: Key): String = sign(signer, "trust-mark+jwt", buildJsonObject {
        put("iss", issuer)
        put("sub", subjectId)
        put("trust_mark_type", markType)
        put("iat", now - 5)
        put("exp", now + 600)
    })

    /** [fetched] is what a separate Entity Configuration fetch of [issuer] returns; [chain] what chain resolution returns. */
    private fun command(fetched: EntityConfigurationStatement?, chain: List<String> = emptyList()): VerifyTrustMarkCommandImpl {
        val client = HttpClient(MockEngine) { engine { addHandler { respondError(HttpStatusCode.NotFound) } } }
        httpClient = client
        val cache = OidfCache.createStringKeyCache(
            manager = OidfCache.newManager(),
            requirements = CacheRequirements(namespace = "trust-mark-issuer-keys-${hashCode()}-${chain.hashCode()}"),
            valueSerializer = HttpMetadataCacheSerializers.stringValue
        )
        val context = FederationContext(jwtService = jwtService,
            httpResolver = HttpResolver(httpClient = client, cache = cache, responseMapper = { it.bodyAsText() }))
        val getEntityConfiguration = mockk<GetEntityConfigurationCommand>()
        coEvery { getEntityConfiguration.getEntityConfiguration(any()) } answers {
            if (fetched != null) IdkResult.ok(fetched) else error("no separate fetch expected")
        }
        val resolve = mockk<ResolveTrustChainCommand>()
        coEvery { resolve.execute(any()) } returns IdkResult.ok(TrustChainResolveResponse(trustChain = chain))
        val verify = mockk<VerifyTrustChainCommand>()
        coEvery { verify.verifyTrustChain(any(), any(), any(), any()) } returns IdkResult.ok(VerifyTrustChainResponse(isValid = true))
        return VerifyTrustMarkCommandImpl(execution, context, getEntityConfiguration, resolve, verify)
    }

    @Test
    fun aMarkSignedWithTheTrustedAnchorKeyIsValid() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId))
        val result = command(fetched = anchor).verifyTrustMark(trustMark(anchorId, anchorKey), anchor, now, subjectId)
        assertTrue(result.isOk && result.value.isValid, "a mark signed by the trusted anchor key must be valid: $result")
    }

    @Test
    fun aMarkSignedWithAKeyOnlyInAFetchedAnchorConfigurationIsRejected() = runTest {
        val anchorKey = key()
        val otherKey = key()
        val trusted = anchorConfiguration(listOf(anchorKey), listOf(anchorId))
        val fetched = anchorConfiguration(listOf(otherKey), listOf(anchorId))
        val result = command(fetched = fetched).verifyTrustMark(trustMark(anchorId, otherKey), trusted, now, subjectId)
        assertTrue(result.isErr || !result.value.isValid, "the trusted anchor configuration, not a fetched copy, supplies the key: $result")
    }

    @Test
    fun anIssuerMarkIsCheckedWithTheKeyInTheVerifiedChain() = runTest {
        val anchorKey = key()
        val chainKey = key()
        val fetchedKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(issuerId))
        val issuerInChain = sign(chainKey, "entity-statement+jwt", configurationClaims(issuerId, listOf(chainKey)))
        val fetched: EntityConfigurationStatement = json.decodeFromJsonElement(configurationClaims(issuerId, listOf(fetchedKey)))
        val chain = listOf(issuerInChain, "subordinate.statement.placeholder", "anchor.configuration.placeholder")

        val signedWithChainKey = command(fetched, chain).verifyTrustMark(trustMark(issuerId, chainKey), anchor, now, subjectId)
        assertTrue(signedWithChainKey.isOk && signedWithChainKey.value.isValid,
            "a mark signed with the issuer key from the verified chain must be valid: $signedWithChainKey")

        val signedWithFetchedKey = command(fetched, chain).verifyTrustMark(trustMark(issuerId, fetchedKey), anchor, now, subjectId)
        assertTrue(signedWithFetchedKey.isErr || !signedWithFetchedKey.value.isValid,
            "a key that appears only in a separately fetched configuration must not verify the mark: $signedWithFetchedKey")
    }
}
