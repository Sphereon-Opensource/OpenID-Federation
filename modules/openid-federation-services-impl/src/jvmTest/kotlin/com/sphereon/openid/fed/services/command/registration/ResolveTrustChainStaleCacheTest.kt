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
 * Key rollover while a resolver holds cached statements: a key mismatch caused by a cached statement is retried once
 * with fresh statements, and a genuine mismatch still fails after that single retry. Real keys and signatures; HTTP is
 * the only simulated boundary.
 */
class ResolveTrustChainStaleCacheTest {
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
        val app = createExactSuperiorEvidenceTestGraph(this@ResolveTrustChainStaleCacheTest)
        val session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "stale-cache-${hashCode()}", principalType = PrincipalType.ANONYMOUS,
        )
        val provider = (app as SoftwareKmsProviderFactoryImpl.Graph).softwareKmsProvider.create(
            SoftwareKmsProviderConfig(id = "stale-cache-provider-${hashCode()}"),
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
            requirements = CacheRequirements(namespace = "stale-cache-${hashCode()}"),
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

    private fun claims(iss: String, sub: String, keys: JsonObject, authorityHints: List<String>? = null) = buildJsonObject {
        put("iss", iss)
        put("sub", sub)
        put("iat", now - 10)
        put("exp", now + 3_600)
        put("jwks", keys)
        if (iss == sub) put("metadata", buildJsonObject {
            put("federation_entity", buildJsonObject { if (iss == ta) put("federation_fetch_endpoint", "$ta/fetch") })
        })
        authorityHints?.let { put("authority_hints", JsonArray(it.map(::JsonPrimitive))) }
    }

    private suspend fun publish(taKey: Key, leafKey: Key, subordinateKeys: JsonObject = jwks(leafKey)) {
        published["$ta/.well-known/openid-federation"] = sign(claims(ta, ta, jwks(taKey)), taKey)
        published["$leaf/.well-known/openid-federation"] = sign(claims(leaf, leaf, jwks(leafKey), listOf(ta)), leafKey)
        published["$ta/fetch?sub=$leaf"] = sign(claims(ta, leaf, subordinateKeys), taKey)
    }

    private fun resolver(): ResolveTrustChainCommandImpl {
        val configurations = GetEntityConfigurationCommandImpl(execution, context)
        return ResolveTrustChainCommandImpl(execution, context, VerifyTrustChainCommandImpl(execution, context),
            PublishedTrustAnchorKeyResolver(configurations))
    }

    private suspend fun resolve() = resolver().execute(ResolveTrustChainArgs(leaf, arrayOf(ta)))

    @Test
    fun cachedSubordinateStatementPredatingTheSubjectKeyRolloverIsRefetchedOnce() = runTest {
        val taKey = key()
        val oldLeafKey = key()
        publish(taKey, oldLeafKey)
        // The resolver cached the superior's statement that vouches only for the previous leaf key.
        context.httpResolver.getJwt("$ta/fetch?sub=$leaf")

        val newLeafKey = key()
        publish(taKey, newLeafKey)
        requested.clear()

        val result = resolve()
        assertTrue(result.isOk, "A stale cached Subordinate Statement must be refetched before failing")
        assertEquals(1, requested.count { it == "$ta/fetch?sub=$leaf" }, "The stale cached read is followed by one refetch")
        assertTrue(resolve().isOk, "The refetched statement replaces the stale cache entry")
        assertEquals(1, requested.count { it == "$ta/fetch?sub=$leaf" }, "Later resolutions use the refreshed entry")
    }

    @Test
    fun cachedTrustAnchorConfigurationPredatingItsKeyRolloverIsRefetchedOnce() = runTest {
        val oldTaKey = key()
        val leafKey = key()
        publish(oldTaKey, leafKey)
        context.httpResolver.getJwt("$ta/.well-known/openid-federation")

        val newTaKey = key()
        publish(newTaKey, leafKey)
        requested.clear()

        val result = resolve()
        assertTrue(result.isOk, "Cached Trust Anchor keys that cannot verify the fresh statement must be refreshed")
        val again = resolve()
        assertTrue(again.isOk)
    }

    @Test
    fun genuineKeyMismatchStillFailsAfterASingleFreshAttempt() = runTest {
        val taKey = key()
        val leafKey = key()
        val unrelated = key()
        publish(taKey, leafKey, subordinateKeys = jwks(unrelated))
        requested.clear()

        val result = resolve()
        assertTrue(result.isErr, "A superior that never vouches for the leaf key cannot form a chain")
        assertEquals(2, requested.count { it == "$ta/fetch?sub=$leaf" }, "One normal and one bypassing attempt, no more")
    }
}
