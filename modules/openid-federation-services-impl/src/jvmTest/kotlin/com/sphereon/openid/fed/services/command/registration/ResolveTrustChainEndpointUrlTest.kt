package com.sphereon.openid.fed.services.command.registration

import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.asCoreApiServiceGraph
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.kms.KeyManagerService
import com.sphereon.crypto.core.kms.asKeyManagerServiceGraph
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.crypto.jose.jws.JwtServiceImpl
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderConfig
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderFactoryImpl
import com.sphereon.di.context.PrincipalType
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommandImpl
import com.sphereon.openid.fed.client.command.trustChain.PublishedTrustAnchorKeyResolver
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainArgs
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommandImpl
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommandImpl
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.core.cache.OidfCache
import com.sphereon.openid.fed.httpResolver.HttpMetadataCacheSerializers
import com.sphereon.openid.fed.httpResolver.HttpResolver
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.services.command.resolution.createExactSuperiorEvidenceTestGraph
import com.sphereon.openid.fed.services.signPayload
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OpenID Federation 1.1 section 5.1.1: federation_fetch_endpoint MUST use https and MUST NOT contain a fragment. A
 * resolver that receives an Entity Configuration advertising another value refuses that endpoint instead of following
 * or repairing it. Real keys and signatures; HTTP is the only simulated boundary.
 */
class ResolveTrustChainEndpointUrlTest {
    private val ta = "https://ta.example"
    private val leaf = "https://leaf.example"
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var jwtService: JwtService
    private lateinit var keyManager: KeyManagerService
    private lateinit var execution: SessionExecution
    private lateinit var context: FederationContext
    private var httpClient: HttpClient? = null
    private val published = mutableMapOf<String, String>()
    private val requested = mutableListOf<String>()
    private var now = 0L

    private data class Key(val kid: String, val alias: String, val provider: String, val public: JsonObject)

    @BeforeTest
    fun setUp() = runTest {
        val app = createExactSuperiorEvidenceTestGraph(this@ResolveTrustChainEndpointUrlTest)
        val session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "endpoint-url-${hashCode()}", principalType = PrincipalType.ANONYMOUS,
        )
        val provider = (app as SoftwareKmsProviderFactoryImpl.Graph).softwareKmsProvider.create(
            SoftwareKmsProviderConfig(id = "endpoint-url-provider-${hashCode()}"),
            session.asCoreApiServiceGraph().serviceExecution,
        )
        keyManager = session.graph.asKeyManagerServiceGraph().keyManagerService
        keyManager.registerProvider(provider, makeDefaultKms = true)
        jwtService = (session.graph as JwtServiceImpl.Graph).jwtService
        execution = session.asCoreApiServiceGraph().serviceExecution
        httpClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val url = request.url
                    val key = if (url.encodedPath == "/fetch") {
                        "${url.protocol.name}://${url.host}/fetch?sub=${url.parameters["sub"]}"
                    } else {
                        "${url.protocol.name}://${url.host}${url.encodedPath}"
                    }
                    requested += key
                    published[key]?.let { body ->
                        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt"))
                    } ?: respond("not published", HttpStatusCode.NotFound)
                }
            }
        }
        val cache = OidfCache.createStringKeyCache(
            manager = OidfCache.newManager(),
            requirements = CacheRequirements(namespace = "endpoint-url-${hashCode()}"),
            valueSerializer = HttpMetadataCacheSerializers.stringValue,
        )
        context = FederationContext(
            jwtService = jwtService,
            httpResolver = HttpResolver(httpClient = requireNotNull(httpClient), cache = cache, responseMapper = { it.bodyAsText() }),
        )
        now = getCurrentEpochTimeSeconds()
    }

    @AfterTest
    fun tearDown() {
        httpClient?.close()
    }

    private suspend fun key(): Key {
        val generated = keyManager.generateKeyAsync(alg = SignatureAlgorithm.ECDSA_SHA256)
        val kid = requireNotNull(generated.kid)
        val public = JsonObject(json.parseToJsonElement(generated.jose.publicJwk.toJsonString()).jsonObject + ("kid" to JsonPrimitive(kid)))
        return Key(kid, requireNotNull(generated.alias), generated.providerId, public)
    }

    private fun jwks(vararg keys: Key) = buildJsonObject { put("keys", JsonArray(keys.map { it.public })) }

    private suspend fun sign(payload: JsonObject, key: Key): String {
        val signed = jwtService.signPayload(payload, JwtHeader(alg = "ES256", kid = key.kid, typ = "entity-statement+jwt"),
            key.kid, key.alias, key.provider)
        assertTrue(signed.isOk, "real signing failed")
        return signed.value
    }

    private fun claims(iss: String, sub: String, keys: JsonObject, fetchEndpoint: String?, authorityHints: List<String>? = null) = buildJsonObject {
        put("iss", iss)
        put("sub", sub)
        put("iat", now - 10)
        put("exp", now + 3_600)
        put("jwks", keys)
        if (iss == sub) put("metadata", buildJsonObject {
            put("federation_entity", buildJsonObject { fetchEndpoint?.let { put("federation_fetch_endpoint", it) } })
        })
        authorityHints?.let { put("authority_hints", JsonArray(it.map(::JsonPrimitive))) }
    }

    /** Publishes the Trust Anchor with [fetchEndpoint] and the leaf's Subordinate Statement at every candidate URL. */
    private suspend fun publish(fetchEndpoint: String) {
        val taKey = key()
        val leafKey = key()
        published["$ta/.well-known/openid-federation"] = sign(claims(ta, ta, jwks(taKey), fetchEndpoint), taKey)
        published["$leaf/.well-known/openid-federation"] = sign(claims(leaf, leaf, jwks(leafKey), null, listOf(ta)), leafKey)
        val statement = sign(claims(ta, leaf, jwks(leafKey), null), taKey)
        published["https://ta.example/fetch?sub=$leaf"] = statement
        published["http://ta.example/fetch?sub=$leaf"] = statement
    }

    private fun resolver(): ResolveTrustChainCommandImpl {
        val configurations = GetEntityConfigurationCommandImpl(execution, context)
        return ResolveTrustChainCommandImpl(execution, context, VerifyTrustChainCommandImpl(execution, context),
            PublishedTrustAnchorKeyResolver(configurations))
    }

    private suspend fun resolve() = resolver().execute(ResolveTrustChainArgs(leaf, arrayOf(ta)))

    @Test
    fun anHttpsFetchEndpointIsFollowed() = runTest {
        publish("$ta/fetch")
        val result = resolve()
        assertTrue(result.isOk && result.value.trustChain.isNotEmpty(), "an https fetch endpoint must resolve: $result")
    }

    @Test
    fun anHttpFetchEndpointIsRefused() = runTest {
        publish("http://ta.example/fetch")
        val result = resolve()
        assertTrue(result.isErr || result.value.trustChain.isEmpty(), "an http fetch endpoint must not yield a chain: $result")
        assertTrue(requested.none { it.startsWith("http://") }, "the http fetch endpoint must not be requested: $requested")
    }

    @Test
    fun aFetchEndpointWithAFragmentIsRefusedNotStripped() = runTest {
        publish("$ta/fetch#statements")
        val result = resolve()
        assertTrue(result.isErr || result.value.trustChain.isEmpty(), "a fragment fetch endpoint must not yield a chain: $result")
        assertTrue(requested.none { it.contains("/fetch") }, "the fetch endpoint must not be requested with its fragment removed: $requested")
    }
}
