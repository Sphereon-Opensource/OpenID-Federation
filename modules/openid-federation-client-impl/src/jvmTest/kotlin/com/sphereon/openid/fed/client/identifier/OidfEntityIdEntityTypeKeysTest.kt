package com.sphereon.openid.fed.client.identifier

import com.sphereon.core.api.asOkResult
import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.core.api.session.asCoreApiServiceGraph
import com.sphereon.crypto.resolution.extern.ExternalIdentifierOIDFEntityIdOpts
import com.sphereon.di.context.PrincipalType
import com.sphereon.openid.fed.client.FederationClient
import com.sphereon.openid.fed.client.command.discovery.DiscoverEntitiesResult
import com.sphereon.openid.fed.client.command.discovery.ListSubordinatesResult
import com.sphereon.openid.fed.client.test.createClientImplTestAppGraph
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.TrustChainResolveResponse
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An Entity Type selects the protocol keys of that type's Resolved Metadata instead of the Entity's federation keys,
 * which sign federation statements only. Chain resolution and verification come from the federation client.
 */
class OidfEntityIdEntityTypeKeysTest {
    private val leaf = "https://verifier.example.com/oid4vp/verifier"
    private val anchor = "https://anchor.example.com/federation"

    private fun key(kid: String, x: String) = buildJsonObject {
        put("kty", "EC"); put("crv", "P-256"); put("kid", kid); put("x", x); put("y", "y-$x")
    }

    private fun jwt(payload: JsonObject): String {
        val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val header = encoder.encode("""{"alg":"ES256","typ":"entity-statement+jwt","kid":"fed"}""".encodeToByteArray())
        return "$header.${encoder.encode(payload.toString().encodeToByteArray())}.c2ln"
    }

    private fun chain(verifierMetadata: JsonObject?, subordinatePolicy: JsonObject? = null): List<String> {
        val leafConfiguration = buildJsonObject {
            put("iss", leaf); put("sub", leaf); put("iat", 1); put("exp", 4102444800)
            putJsonObject("jwks") { putJsonArray("keys") { add(key("federation-key", "fed")) } }
            putJsonObject("metadata") { verifierMetadata?.let { put("openid_credential_verifier", it) } }
            putJsonArray("authority_hints") { add(kotlinx.serialization.json.JsonPrimitive(anchor)) }
        }
        val subordinate = buildJsonObject {
            put("iss", anchor); put("sub", leaf); put("iat", 1); put("exp", 4102444800)
            putJsonObject("jwks") { putJsonArray("keys") { add(key("federation-key", "fed")) } }
            subordinatePolicy?.let { put("metadata_policy", it) }
        }
        val anchorConfiguration = buildJsonObject {
            put("iss", anchor); put("sub", anchor); put("iat", 1); put("exp", 4102444800)
            putJsonObject("jwks") { putJsonArray("keys") { add(key("anchor-key", "ta")) } }
        }
        return listOf(jwt(leafConfiguration), jwt(subordinate), jwt(anchorConfiguration))
    }

    private fun resolver(trustChain: List<String>): OidfEntityIdExternalIdentifierResolutionServiceImpl {
        val namespace = "federation-client-impl-test.test"
        DefaultAppMapPropertySource.addProperties(
            mapOf(
                "$namespace.kms.providers.memory.type" to "software",
                "$namespace.kms.providers.memory.id" to "memory",
                "$namespace.kms.providers.memory.enabled" to "true",
                "$namespace.kms.providers.memory.order" to "100",
                "$namespace.kms.providers.memory.keystore.type" to "memory",
                "$namespace.kms.providers.memory.keystore.id" to "oidf-memory-keystore",
                "$namespace.kms.providers.memory.keystore.keyvisibility" to "private",
                "$namespace.kms.providers.memory.keystore.scopebinding" to "app",
                "$namespace.kms.keystores.oidf-memory-keystore.type" to "memory",
                "$namespace.kms.keystores.oidf-memory-keystore.id" to "oidf-memory-keystore",
                "$namespace.kms.keystores.oidf-memory-keystore.keyvisibility" to "private",
                "$namespace.kms.keystores.oidf-memory-keystore.scopebinding" to "app",
            ),
        )
        val app = createClientImplTestAppGraph(application = this, appId = "federation-client-impl-test", profile = "test", version = "1.0.0")
        val session = app.userContextManager.getAnonymous().sessionContextManager
            .createOrGetFromId(sessionId = "oidf-entity-type-keys", principalType = PrincipalType.ANONYMOUS)
        val client = object : FederationClient {
            override suspend fun trustChainResolve(entityIdentifier: String, trustAnchors: Array<String>, maxDepth: Int) =
                TrustChainResolveResponse(trustChain = trustChain).asOkResult() as FederationResult<TrustChainResolveResponse>
            override suspend fun trustChainVerify(trustChain: Array<String>, trustAnchor: String?, currentTime: Long?, trustAnchorPublicKeys: List<Jwk>?) =
                VerifyTrustChainResponse(isValid = true).asOkResult() as FederationResult<VerifyTrustChainResponse>
            override suspend fun entityConfigurationStatementGet(entityIdentifier: String): FederationResult<EntityConfigurationStatement> = unused()
            override suspend fun trustMarksVerify(trustMark: String, trustAnchorConfig: EntityConfigurationStatement, currentTime: Long?, subject: String?): FederationResult<TrustMarkValidationResponse> = unused()
            override suspend fun listSubordinates(superiorEntityId: String, entityType: String?, trustMarked: Boolean?, trustMarkType: String?, intermediate: Boolean?): FederationResult<ListSubordinatesResult> = unused()
            override suspend fun discoverEntities(startEntityId: String, entityType: String?, recursive: Boolean, maxDepth: Int, verifyTrust: Boolean, trustAnchors: Array<String>, maxDepthTrustChain: Int): FederationResult<DiscoverEntitiesResult> = unused()
        }
        return OidfEntityIdExternalIdentifierResolutionServiceImpl(session.asCoreApiServiceGraph().serviceExecution, lazy { client })
    }

    private fun <T> unused(): T = throw UnsupportedOperationException("not used by the entity-id resolver")

    @Test
    fun entityTypeSelectsTheResolvedMetadataKeysNotTheFederationKeys() = runTest {
        val metadata = buildJsonObject { putJsonObject("jwks") { putJsonArray("keys") { add(key("request-signing", "rp")) } } }
        val result = resolver(chain(metadata)).resolve(
            ExternalIdentifierOIDFEntityIdOpts(identifier = leaf, trustAnchors = listOf(anchor), entityType = "openid_credential_verifier"),
        )
        assertTrue(result.isOk, "resolution: ${if (result.isErr) result.error.message.defaultMessage else ""}")
        assertEquals(listOf("request-signing"), result.value.jwks.map { it.key.kid?.toString() })
        assertTrue(result.value.trustEstablished)
    }

    @Test
    fun withoutEntityTypeTheFederationKeysAreReturnedAsBefore() = runTest {
        val metadata = buildJsonObject { putJsonObject("jwks") { putJsonArray("keys") { add(key("request-signing", "rp")) } } }
        val result = resolver(chain(metadata)).resolve(ExternalIdentifierOIDFEntityIdOpts(identifier = leaf, trustAnchors = listOf(anchor)))
        assertTrue(result.isOk)
        assertEquals(listOf("federation-key"), result.value.jwks.map { it.key.kid?.toString() })
    }

    @Test
    fun anEntityWithoutTheRequestedTypeIsRefused() = runTest {
        val result = resolver(chain(verifierMetadata = null)).resolve(
            ExternalIdentifierOIDFEntityIdOpts(identifier = leaf, trustAnchors = listOf(anchor), entityType = "openid_credential_verifier"),
        )
        assertTrue(result.isErr)
        assertTrue(result.error.message.defaultMessage.contains("openid_credential_verifier"), result.error.message.defaultMessage)
    }

    @Test
    fun aMetadataPolicyTheMetadataViolatesIsRefused() = runTest {
        val metadata = buildJsonObject {
            putJsonObject("jwks") { putJsonArray("keys") { add(key("request-signing", "rp")) } }
            put("client_name", "Verifier")
        }
        val policy = buildJsonObject {
            putJsonObject("openid_credential_verifier") {
                putJsonObject("client_name") { put("one_of", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("Another verifier")) }) }
            }
        }
        val result = resolver(chain(metadata, policy)).resolve(
            ExternalIdentifierOIDFEntityIdOpts(identifier = leaf, trustAnchors = listOf(anchor), entityType = "openid_credential_verifier"),
        )
        assertTrue(result.isErr, "a one_of the metadata value is not among must not resolve")
        assertTrue(result.error.message.defaultMessage.contains("do not resolve"), result.error.message.defaultMessage)
    }
}
