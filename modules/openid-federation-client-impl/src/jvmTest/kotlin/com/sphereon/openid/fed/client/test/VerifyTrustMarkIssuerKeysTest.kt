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
import com.sphereon.openid.fed.client.command.trustMark.TrustMarkStatusPolicy
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkArgs
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkCommandImpl
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.core.cache.OidfCache
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.httpResolver.HttpMetadataCacheSerializers
import com.sphereon.openid.fed.httpResolver.HttpResolver
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.TrustChainResolveResponse
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OpenID Federation 1.1 section 7.3: trust in the Trust Mark issuer comes first, and the mark's signature is checked
 * with that trusted issuer's key. For the Trust Anchor that is the Trust Anchor Entity Configuration the caller trusts;
 * for another issuer it is the issuer's Entity Configuration inside the verified Trust Chain. A separately fetched
 * Entity Configuration, which is only self-signed, must not supply the key.
 */
abstract class TrustMarkVerificationTestFixture {
    protected val anchorId = "https://anchor.example"
    protected val issuerId = "https://issuer.example"
    protected val subjectId = "https://leaf.example"
    protected val markType = "https://anchor.example/marks/member"
    protected val criteriaReference = "https://anchor.example/criteria/member-v1"
    protected val ownerId = "https://owner.example"
    protected val now = 1_800_000_000L
    protected val statusEndpoint = "https://anchor.example/trust-mark/status"
    protected val json = Json { ignoreUnknownKeys = true }

    protected lateinit var jwtService: JwtService
    protected lateinit var keyManager: com.sphereon.crypto.core.kms.KeyManagerService
    protected lateinit var execution: SessionExecution
    protected val httpClients = mutableListOf<HttpClient>()

    protected data class Key(val signing: ManagedKeyInfoType<*>, val publicJson: JsonElement)

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
        httpClients.forEach(HttpClient::close)
    }

    protected suspend fun key(): Key {
        val pair = keyManager.generateKeyAsync(alg = SignatureAlgorithm.ECDSA_SHA256)
        val publicJwk = pair.joseToManagedKeyInfo(KeyVisibility.PUBLIC).key as CryptoJwk
        return Key(pair.joseToManagedKeyInfo(KeyVisibility.PRIVATE),
            cryptoJsonSerializer.encodeToJsonElement(CryptoJwk.serializer(), publicJwk))
    }

    protected suspend fun sign(signer: Key, typ: String, claims: JsonObject): String {
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

    protected fun configurationClaims(entity: String, keys: List<Key>, extra: JsonObject = JsonObject(emptyMap())) = JsonObject(buildJsonObject {
        put("iss", entity)
        put("sub", entity)
        put("iat", now - 10)
        put("exp", now + 3600)
        put("jwks", buildJsonObject { put("keys", JsonArray(keys.map { it.publicJson })) })
        put("metadata", buildJsonObject { put("federation_entity", JsonObject(emptyMap())) })
    } + extra)

    protected fun anchorConfiguration(
        keys: List<Key>,
        issuers: List<String>,
        advertiseStatus: Boolean = false,
        statusUrl: String = statusEndpoint,
    ): EntityConfigurationStatement = json.decodeFromJsonElement(
        configurationClaims(anchorId, keys, buildJsonObject {
            put("trust_mark_issuers", buildJsonObject { put(markType, JsonArray(issuers.map { kotlinx.serialization.json.JsonPrimitive(it) })) })
            if (advertiseStatus) {
                put("metadata", buildJsonObject {
                    put("federation_entity", buildJsonObject { put("federation_trust_mark_status_endpoint", statusUrl) })
                })
            }
        })
    )

    protected fun ownedMarkAnchor(anchorKey: Key, ownerKey: Key): EntityConfigurationStatement = json.decodeFromJsonElement(
        configurationClaims(anchorId, listOf(anchorKey), buildJsonObject {
            put("trust_mark_owners", buildJsonObject {
                put(markType, buildJsonObject {
                    put("sub", ownerId)
                    put("jwks", buildJsonObject { put("keys", JsonArray(listOf(ownerKey.publicJson))) })
                })
            })
            put("metadata", buildJsonObject {
                put("federation_entity", buildJsonObject { put("federation_trust_mark_status_endpoint", statusEndpoint) })
            })
        })
    )

    protected suspend fun delegation(
        signer: Key,
        owner: String = ownerId,
        delegate: String = anchorId,
        type: String = markType,
    ): String = sign(signer, "trust-mark-delegation+jwt", buildJsonObject {
        put("iss", owner)
        put("sub", delegate)
        put("trust_mark_type", type)
        put("iat", now - 10)
        put("exp", now + 300)
    })

    protected suspend fun trustMark(
        issuer: String,
        signer: Key,
        issuedAt: Long = now - 5,
        expiry: Long = now + 600,
        delegation: String? = null,
        type: String = markType,
    ): String = sign(signer, "trust-mark+jwt", buildJsonObject {
        put("iss", issuer)
        put("sub", subjectId)
        put("trust_mark_type", type)
        put("ref", criteriaReference)
        put("iat", issuedAt)
        put("exp", expiry)
        if (delegation != null) {
            put("delegation", delegation)
        }
    })

    /** [fetched] is what a separate Entity Configuration fetch of [issuer] returns; [chain] what chain resolution returns. */
    protected fun command(
        fetched: EntityConfigurationStatement?,
        chain: List<String> = emptyList(),
        statusResponse: String? = null,
        statusFailure: Throwable? = null,
        expectedMark: String? = null,
        chainFailure: FederationError? = null,
        statusResponses: List<String> = emptyList(),
    ): VerifyTrustMarkCommandImpl {
        var statusIndex = 0
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    if (expectedMark != null) {
                        assertEquals(statusEndpoint, request.url.toString())
                        assertEquals(HttpMethod.Post, request.method)
                        val body = request.body as OutgoingContent.ByteArrayContent
                        assertEquals(expectedMark, parseQueryString(body.bytes().decodeToString())["trust_mark"])
                    }
                    if (statusFailure != null) {
                        throw statusFailure
                    }
                    val response = if (statusResponses.isEmpty()) statusResponse else {
                        check(statusIndex < statusResponses.size) { "Unexpected extra status request" }
                        statusResponses[statusIndex++]
                    }
                    if (response == null) {
                        respondError(HttpStatusCode.NotFound)
                    } else {
                        respond(response, HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/trust-mark-status-response+jwt"))
                    }
                }
            }
        }
        httpClients.add(client)
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
        coEvery { resolve.execute(any()) } answers {
            if (chainFailure != null) IdkResult.err(chainFailure)
            else IdkResult.ok(TrustChainResolveResponse(trustChain = chain))
        }
        val verify = mockk<VerifyTrustChainCommand>()
        coEvery { verify.verifyTrustChain(any(), any(), any(), any()) } returns IdkResult.ok(VerifyTrustChainResponse(isValid = true))
        return VerifyTrustMarkCommandImpl(execution, context, getEntityConfiguration, resolve, verify)
    }

    protected suspend fun statusResponse(
        signer: Key,
        mark: String,
        issuedAt: Long? = now - 10,
        status: String = "active",
        expiry: Long = now + 120,
        issuer: String = anchorId,
    ): String = sign(signer, "trust-mark-status-response+jwt", buildJsonObject {
        put("iss", issuer)
        put("trust_mark", mark)
        if (issuedAt != null) {
            put("iat", issuedAt)
        }
        put("exp", expiry)
        put("status", status)
    })

    protected suspend fun strictResult(
        anchor: EntityConfigurationStatement,
        mark: String,
        response: String?,
        subject: String? = subjectId,
        chain: List<String> = emptyList(),
        policy: TrustMarkStatusPolicy = TrustMarkStatusPolicy(maximumAgeSeconds = 60, allowedClockSkewSeconds = 5),
    ): JsonObject {
        val result = command(fetched = anchor, chain = chain, statusResponse = response, expectedMark = mark).execute(
            VerifyTrustMarkArgs(
                trustMark = mark,
                trustAnchorConfig = anchor,
                currentTime = now,
                subject = subject,
                statusPolicy = policy,
            )
        )
        assertTrue(result.isOk, "strict validation must return a typed outcome: $result")
        return json.encodeToJsonElement(result.value).jsonObject
    }

    protected fun assertRejected(response: JsonObject, outcome: String, failureCode: String) {
        assertEquals("false", response["isValid"]?.jsonPrimitive?.content)
        assertEquals(outcome, response["outcome"]?.jsonPrimitive?.content)
        assertEquals(failureCode, response["failureCode"]?.jsonPrimitive?.content)
        assertTrue(response["verifiedEvidence"] == null || response["verifiedEvidence"] is JsonNull,
            "a rejected mark cannot produce verified evidence")
        val attempted = response["attemptedEvidence"]
        if (attempted is JsonObject) {
            assertEquals("UNVERIFIED", attempted["trustLevel"]?.jsonPrimitive?.content)
        }
    }

}

class VerifyTrustMarkIssuerKeysTest : TrustMarkVerificationTestFixture() {
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
