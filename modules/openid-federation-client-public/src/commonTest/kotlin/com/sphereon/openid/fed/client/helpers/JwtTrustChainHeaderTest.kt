package com.sphereon.openid.fed.client.helpers

import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.JwtHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class JwtTrustChainHeaderTest {

    @Test
    fun extractFromTypedHeader() {
        val chain = listOf("eyJ.leaf.sig", "eyJ.ta.sig")
        val jwt = Jwt(
            header = JwtHeader(
                alg = "ES256",
                kid = "k1",
                typ = "oauth-client-attestation+jwt",
                trustChain = chain,
            ),
            payload = JsonObject(mapOf("iss" to JsonPrimitive("https://wp.example"))),
            signature = "sig",
        )
        assertEquals(chain, JwtTrustChainHeader.extract(jwt))
        assertEquals(chain, JwtTrustChainHeader.extract(jwt.header))
    }

    @Test
    fun extractAbsentReturnsNull() {
        val jwt = Jwt(
            header = JwtHeader(alg = "ES256", kid = "k1", typ = "JWT"),
            payload = JsonObject(emptyMap()),
            signature = "sig",
        )
        assertNull(JwtTrustChainHeader.extract(jwt))
        assertFalse(JwtTrustChainHeader.extractAll(jwt).hasAny)
    }

    @Test
    fun extractEmptyListReturnsNull() {
        val jwt = Jwt(
            header = JwtHeader(
                alg = "ES256",
                kid = "k1",
                typ = "JWT",
                trustChain = emptyList(),
            ),
            payload = JsonObject(emptyMap()),
            signature = "sig",
        )
        assertNull(JwtTrustChainHeader.extract(jwt))
    }

    @Test
    fun extractPeerTrustChain() {
        val peer = listOf("eyJ.peer.sig")
        val jwt = Jwt(
            header = JwtHeader(
                alg = "ES256",
                kid = "k1",
                typ = "JWT",
                peerTrustChain = peer,
            ),
            payload = JsonObject(emptyMap()),
            signature = "sig",
        )
        assertEquals(peer, JwtTrustChainHeader.extractPeer(jwt))
        val all = JwtTrustChainHeader.extractAll(jwt)
        assertNull(all.trustChain)
        assertEquals(peer, all.peerTrustChain)
        assertTrue(all.hasAny)
    }

    @Test
    fun extractFromCompactJwt() {
        val chain = listOf("leaf.jwt", "ta.jwt")
        val header = buildMap {
            put("alg", JsonPrimitive("ES256"))
            put("kid", JsonPrimitive("k1"))
            put("typ", JsonPrimitive("entity-statement+jwt"))
            put(
                "trust_chain",
                kotlinx.serialization.json.JsonArray(chain.map { JsonPrimitive(it) }),
            )
        }
        val headerB64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode(Json.encodeToString(JsonObject.serializer(), JsonObject(header)).encodeToByteArray())
        val payloadB64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode("""{"iss":"https://example"}""".encodeToByteArray())
        val compact = "$headerB64.$payloadB64.sig"

        assertEquals(chain, JwtTrustChainHeader.extractFromCompactJwt(compact))
        assertEquals(chain, JwtTrustChainHeader.extractAllFromCompactJwt(compact).trustChain)
    }
}
