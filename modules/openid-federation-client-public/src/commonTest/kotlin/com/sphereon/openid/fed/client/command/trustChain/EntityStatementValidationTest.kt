package com.sphereon.openid.fed.client.command.trustChain

import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityStatementValidationTest {

    @OptIn(ExperimentalEncodingApi::class)
    private fun compact(header: String, payload: String): String {
        val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        return "${encoder.encode(header.encodeToByteArray())}.${encoder.encode(payload.encodeToByteArray())}.signature"
    }

    private fun jwt(
        typ: String? = "entity-statement+jwt",
        alg: String = "ES256",
        kid: String = "k1",
        payload: Map<String, kotlinx.serialization.json.JsonElement>
    ): Jwt {
        return Jwt(
            header = JwtHeader(alg = alg, kid = kid, typ = typ),
            payload = JsonObject(payload),
            signature = "sig"
        )
    }

    private fun baseClaims(
        iss: String,
        sub: String,
        extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
    ): Map<String, kotlinx.serialization.json.JsonElement> {
        val now = 1_700_000_000L
        return mapOf(
            "iss" to JsonPrimitive(iss),
            "sub" to JsonPrimitive(sub),
            "iat" to JsonPrimitive(now - 100),
            "exp" to JsonPrimitive(now + 3600),
            "jwks" to JsonObject(mapOf("keys" to JsonArray(emptyList())))
        ) + extra
    }

    @Test
    fun rejectsEmptyTrustAnchorHintsOnEntityConfiguration() {
        val result = EntityStatementValidation.validateStructure(
            jwt(
                payload = baseClaims(
                    "https://leaf.example",
                    "https://leaf.example",
                    mapOf("trust_anchor_hints" to JsonArray(emptyList()))
                )
            ),
            currentTimeSeconds = 1_700_000_000L,
            position = 0
        )
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("trust_anchor_hints"))
    }

    @Test
    fun acceptsNonEmptyTrustAnchorHintsOnEntityConfiguration() {
        val result = EntityStatementValidation.validateStructure(
            jwt(
                payload = baseClaims(
                    "https://leaf.example",
                    "https://leaf.example",
                    mapOf(
                        "trust_anchor_hints" to JsonArray(
                            listOf(JsonPrimitive("https://ta.example"))
                        )
                    )
                )
            ),
            currentTimeSeconds = 1_700_000_000L,
            position = 0
        )
        assertTrue(result.ok, result.reason)
    }

    @Test
    fun rejectsUnsupportedCriticalClaim() {
        val result = EntityStatementValidation.validateStructure(
            jwt(
                payload = baseClaims(
                    "https://a",
                    "https://a",
                    mapOf(
                        "crit" to JsonArray(listOf(JsonPrimitive("jti"))),
                        "jti" to JsonPrimitive("id-1"),
                    )
                )
            ),
            currentTimeSeconds = 1_700_000_000L,
            position = 0,
            understoodCriticalClaims = emptySet(),
        )
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("unsupported critical claim"))
    }

    @Test
    fun acceptsUnderstoodCriticalClaimWhenPresent() {
        val result = EntityStatementValidation.validateStructure(
            jwt(
                payload = baseClaims(
                    "https://a",
                    "https://a",
                    mapOf(
                        "crit" to JsonArray(listOf(JsonPrimitive("jti"))),
                        "jti" to JsonPrimitive("id-1"),
                    )
                )
            ),
            currentTimeSeconds = 1_700_000_000L,
            position = 0,
            understoodCriticalClaims = setOf("jti"),
        )
        assertTrue(result.ok, result.reason)
    }

    @Test
    fun rejectsCriticalClaimListedButMissing() {
        val result = EntityStatementValidation.validateStructure(
            jwt(
                payload = baseClaims(
                    "https://a",
                    "https://a",
                    mapOf("crit" to JsonArray(listOf(JsonPrimitive("jti"))))
                )
            ),
            currentTimeSeconds = 1_700_000_000L,
            position = 0,
            understoodCriticalClaims = setOf("jti"),
        )
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("missing"))
    }

    @Test
    fun rejectsWrongTyp() {
        val result = EntityStatementValidation.validateStructure(
            jwt(typ = "JWT", payload = baseClaims("https://a", "https://a")),
            currentTimeSeconds = 1_700_000_000L,
            position = 0
        )
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("typ"))
    }

    @Test
    fun rejectsAlgNone() {
        val result = EntityStatementValidation.validateStructure(
            jwt(alg = "none", payload = baseClaims("https://a", "https://a")),
            currentTimeSeconds = 1_700_000_000L,
            position = 0
        )
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("none"))
    }

    @Test
    fun rejectsNonemptyTrustChainHeadersOnEntityConfigurationsAndSubordinateStatements() {
        for ((iss, sub, position) in listOf(
            Triple("https://leaf", "https://leaf", 0),
            Triple("https://superior", "https://leaf", 1),
        )) {
            for (headerName in listOf("trust_chain", "peer_trust_chain")) {
                val header = JwtHeader(
                    alg = "ES256",
                    kid = "k1",
                    typ = "entity-statement+jwt",
                    trustChain = if (headerName == "trust_chain") listOf("signed-chain") else null,
                    peerTrustChain = if (headerName == "peer_trust_chain") listOf("signed-chain") else null,
                )
                val result = EntityStatementValidation.validateStructure(
                    Jwt(header, JsonObject(baseClaims(iss, sub)), "sig"),
                    currentTimeSeconds = 1_700_000_000L,
                    position = position,
                )
                assertFalse(result.ok, "$headerName on statement position $position must be rejected")
                assertTrue(result.reason.orEmpty().contains(headerName), result.reason)
            }
        }
    }

    @Test
    fun rejectsEmptyTrustChainHeadersOnEntityConfigurationsAndSubordinateStatements() {
        for ((iss, sub, position) in listOf(
            Triple("https://leaf", "https://leaf", 0),
            Triple("https://superior", "https://leaf", 1),
        )) {
            for (headerName in listOf("trust_chain", "peer_trust_chain")) {
                val header = JwtHeader(
                    alg = "ES256",
                    kid = "k1",
                    typ = "entity-statement+jwt",
                    trustChain = if (headerName == "trust_chain") emptyList() else null,
                    peerTrustChain = if (headerName == "peer_trust_chain") emptyList() else null,
                )
                val result = EntityStatementValidation.validateStructure(
                    Jwt(header, JsonObject(baseClaims(iss, sub)), "sig"),
                    currentTimeSeconds = 1_700_000_000L,
                    position = position,
                )
                assertFalse(result.ok, "$headerName on statement position $position must be rejected even when empty")
                assertTrue(result.reason.orEmpty().contains(headerName), result.reason)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun decoderRejectsPresentNullTrustChainHeadersOnBothStatementKinds() {
        val entityConfiguration = """{"iss":"https://leaf","sub":"https://leaf"}"""
        val subordinateStatement = """{"iss":"https://superior","sub":"https://leaf"}"""
        for (payload in listOf(entityConfiguration, subordinateStatement)) {
            for (headerName in listOf("trust_chain", "peer_trust_chain")) {
                val header = """{"alg":"ES256","kid":"k1","typ":"entity-statement+jwt","$headerName":null}"""
                assertFails("Present $headerName must be rejected, including JSON null") {
                    decodeJWTComponents(compact(header, payload))
                }
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun decoderAcceptsUnrelatedNullHeaderExtension() {
        val header = """{"alg":"ES256","kid":"k1","typ":"entity-statement+jwt","unrelated_extension":null}"""
        val payload = """{"iss":"https://leaf","sub":"https://leaf"}"""
        val decoded = decodeJWTComponents(compact(header, payload))
        assertEquals(JsonPrimitive("https://leaf"), decoded.payload["sub"])
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun decoderPreservesTrustChainExtensionsOnNonEntityStatementJwtTypes() {
        val payload = """{"iss":"https://issuer.example","sub":"https://subject.example"}"""
        for ((typ, headerName) in listOf(
            "oauth-client-attestation+jwt" to "trust_chain",
            "resolve-response+jwt" to "peer_trust_chain",
        )) {
            val header = """{"alg":"ES256","kid":"k1","typ":"$typ","$headerName":["signed-chain"]}"""
            val decoded = decodeJWTComponents(compact(header, payload))
            assertEquals(typ, decoded.header.typ)
            assertEquals(
                listOf("signed-chain"),
                if (headerName == "trust_chain") decoded.header.trustChain else decoded.header.peerTrustChain
            )
        }
    }

    @Test
    fun rejectsMetadataPolicyOnEntityConfiguration() {
        val result = EntityStatementValidation.validateStructure(
            jwt(
                payload = baseClaims(
                    "https://a", "https://a",
                    mapOf("metadata_policy" to JsonObject(emptyMap()))
                )
            ),
            currentTimeSeconds = 1_700_000_000L,
            position = 0
        )
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("metadata_policy"))
    }

    @Test
    fun rejectsAuthorityHintsOnSubordinateStatement() {
        val result = EntityStatementValidation.validateStructure(
            jwt(
                payload = baseClaims(
                    "https://ta", "https://leaf",
                    mapOf("authority_hints" to JsonArray(listOf(JsonPrimitive("https://x"))))
                )
            ),
            currentTimeSeconds = 1_700_000_000L,
            position = 1
        )
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("authority_hints"))
    }

    @Test
    fun acceptsValidEntityConfiguration() {
        val result = EntityStatementValidation.validateStructure(
            jwt(
                payload = baseClaims(
                    "https://leaf", "https://leaf",
                    mapOf("authority_hints" to JsonArray(listOf(JsonPrimitive("https://ta"))))
                )
            ),
            currentTimeSeconds = 1_700_000_000L,
            position = 0
        )
        assertTrue(result.ok, result.reason)
    }

    @Test
    fun authorityHintsMustListSuperior() {
        val leaf = jwt(
            payload = baseClaims(
                "https://leaf", "https://leaf",
                mapOf("authority_hints" to JsonArray(listOf(JsonPrimitive("https://other"))))
            )
        )
        val ss = jwt(payload = baseClaims("https://ta", "https://leaf"))
        val result = EntityStatementValidation.validateAuthorityHintsLink(leaf, ss)
        assertFalse(result.ok)
    }

    @Test
    fun authorityHintsAcceptsSuperior() {
        val leaf = jwt(
            payload = baseClaims(
                "https://leaf", "https://leaf",
                mapOf("authority_hints" to JsonArray(listOf(JsonPrimitive("https://ta"))))
            )
        )
        val ss = jwt(payload = baseClaims("https://ta", "https://leaf"))
        assertTrue(EntityStatementValidation.validateAuthorityHintsLink(leaf, ss).ok)
    }

    @Test
    fun federationEntityAlwaysAllowed() {
        assertTrue(EntityStatementValidation.isEntityTypeAllowed("federation_entity", emptyList()))
        assertTrue(EntityStatementValidation.isEntityTypeAllowed("federation_entity", listOf("openid_provider")))
        assertFalse(EntityStatementValidation.isEntityTypeAllowed("openid_provider", emptyList()))
        assertTrue(EntityStatementValidation.isEntityTypeAllowed("openid_provider", listOf("openid_provider")))
        assertTrue(EntityStatementValidation.isEntityTypeAllowed("openid_provider", null))
    }
}
