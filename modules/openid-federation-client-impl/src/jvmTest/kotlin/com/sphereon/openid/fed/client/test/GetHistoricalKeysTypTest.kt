package com.sphereon.openid.fed.client.test

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
import com.sphereon.openid.fed.client.command.entityConfiguration.GetFederationEndpointsCommandImpl
import com.sphereon.openid.fed.client.command.entityConfiguration.GetHistoricalKeysCommandImpl
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.core.cache.OidfCache
import com.sphereon.openid.fed.httpResolver.HttpMetadataCacheSerializers
import com.sphereon.openid.fed.httpResolver.HttpResolver
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlin.test.assertTrue

/** OpenID Federation 1.1 section 8.7.2: a historical keys JWT without typ jwk-set+jwt must be rejected. */
class GetHistoricalKeysTypTest {
    private val entityId = "https://entity.example"
    private val endpoint = "https://entity.example/historical-keys"
    private val now = System.currentTimeMillis() / 1000
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var jwtService: JwtService
    private lateinit var keyManager: com.sphereon.crypto.core.kms.KeyManagerService
    private lateinit var execution: SessionExecution
    private var httpClient: HttpClient? = null

    @BeforeTest
    fun setUp() {
        val app = createClientImplTestAppGraph(this)
        val session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "historical-keys-typ-${hashCode()}", principalType = PrincipalType.ANONYMOUS
        )
        val provider = (app as SoftwareKmsProviderFactoryImpl.Graph).softwareKmsProvider.create(
            SoftwareKmsProviderConfig(id = "historical-keys-typ-${hashCode()}"),
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

    private suspend fun signingKey(): Pair<ManagedKeyInfoType<*>, JsonElement> {
        val pair = keyManager.generateKeyAsync(alg = SignatureAlgorithm.ECDSA_SHA256)
        val signing: ManagedKeyInfoType<*> = pair.joseToManagedKeyInfo(KeyVisibility.PRIVATE)
        val publicJwk = pair.joseToManagedKeyInfo(KeyVisibility.PUBLIC).key as CryptoJwk
        return signing to cryptoJsonSerializer.encodeToJsonElement(CryptoJwk.serializer(), publicJwk)
    }

    private suspend fun historicalKeysJwt(signing: ManagedKeyInfoType<*>, publicJwk: JsonElement, typ: String?): String {
        val retired = JsonObject(publicJwk.jsonObject + ("exp" to JsonPrimitive(now - 60)))
        val claims = buildJsonObject {
            put("iss", entityId)
            put("iat", now)
            put("keys", JsonArray(listOf(retired)))
        }
        val header = buildJsonObject {
            typ?.let { put("typ", it) }
            put("kid", signing.kid ?: error("generated key has no kid"))
        }
        val result = jwtService.createJwsCompact(
            CreateJwsArgs(
                issuer = ManagedOptsKeyInfo(
                    identifier = signing,
                    context = IdentifierContext(clientId = "historical-keys-test", clientIdScheme = "jwt_vc_json", issuer = entityId)
                ),
                payload = claims,
                mode = JwsIdentifierMode.KID,
                opts = CreateJwsOpts(noIssPayloadUpdate = true, protectedHeader = header)
            )
        )
        assertTrue(result.isOk, "signing failed: ${if (result.isErr) result.error else ""}")
        return result.value.jwt
    }

    private fun entityConfiguration(publicJwk: JsonElement): EntityConfigurationStatement = json.decodeFromJsonElement(
        buildJsonObject {
            put("iss", entityId)
            put("sub", entityId)
            put("iat", now)
            put("exp", now + 3600)
            put("jwks", buildJsonObject { put("keys", JsonArray(listOf(publicJwk))) })
            put("metadata", buildJsonObject {
                put("federation_entity", buildJsonObject { put("federation_historical_keys_endpoint", endpoint) })
            })
        }
    )

    private fun command(responseJwt: String): GetHistoricalKeysCommandImpl {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(responseJwt, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/jwk-set+jwt"))
                }
            }
        }
        httpClient = client
        val cache = OidfCache.createStringKeyCache(
            manager = OidfCache.newManager(),
            requirements = CacheRequirements(namespace = "historical-keys-typ-test-${hashCode()}-${responseJwt.hashCode()}"),
            valueSerializer = HttpMetadataCacheSerializers.stringValue
        )
        val resolver = HttpResolver(httpClient = client, cache = cache, responseMapper = { it.bodyAsText() })
        val context = FederationContext(jwtService = jwtService, httpResolver = resolver)
        return GetHistoricalKeysCommandImpl(execution, context, GetFederationEndpointsCommandImpl(execution, context))
    }

    @Test
    fun historicalKeysWithJwkSetTypAreAccepted() = runTest {
        val (signing, publicJwk) = signingKey()
        val result = command(historicalKeysJwt(signing, publicJwk, "jwk-set+jwt")).getHistoricalKeys(entityConfiguration(publicJwk))
        assertTrue(result.isOk, "a correctly typed historical keys JWT must be accepted: ${if (result.isErr) result.error else ""}")
        assertEquals(1, result.value.size)
    }

    @Test
    fun historicalKeysWithoutTypAreRejected() = runTest {
        val (signing, publicJwk) = signingKey()
        val result = command(historicalKeysJwt(signing, publicJwk, null)).getHistoricalKeys(entityConfiguration(publicJwk))
        assertTrue(result.isErr, "a historical keys JWT without typ must be rejected")
    }

    @Test
    fun historicalKeysWithAnotherTypAreRejected() = runTest {
        val (signing, publicJwk) = signingKey()
        for (typ in listOf("JWT", "entity-statement+jwt", "JWK-SET+JWT")) {
            val result = command(historicalKeysJwt(signing, publicJwk, typ)).getHistoricalKeys(entityConfiguration(publicJwk))
            assertTrue(result.isErr, "a historical keys JWT with typ '$typ' must be rejected")
        }
    }
}
