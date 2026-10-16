package com.sphereon.openid.fed.client.command.trustChain

import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityStatementValidationTest {

    @Test
    fun numericDatesPermitFractionalSecondsAsDefinedByRfc7519() {
        val claims = baseClaims("https://entity.example", "https://entity.example", mapOf(
            "iat" to JsonPrimitive(1_699_999_999.25),
            "exp" to JsonPrimitive(1_700_000_060.75),
        ))
        assertTrue(EntityStatementValidation.validateStructure(jwt(payload = claims), 1_700_000_000L, 0).ok)
        assertEquals(1_700_000_060L, EntityStatementValidation.conservativeExpiryEpochSeconds(claims["exp"]))
    }

    @Test
    fun entityConfigurationRequiresAnEntityTypeButSubordinateMetadataIsOptional() {
        val claims = baseClaims("https://entity.example", "https://entity.example")
        for (missing in listOf(claims - "metadata", claims + ("metadata" to JsonObject(emptyMap())))) {
            assertFalse(EntityStatementValidation.validateStructure(jwt(payload = missing), 1_700_000_000L, 0).ok)
        }
        assertTrue(EntityStatementValidation.validateStructure(jwt(payload =
            baseClaims("https://superior.example", "https://entity.example") - "metadata"), 1_700_000_000L, 1).ok)
        assertTrue(EntityStatementValidation.validateStructure(jwt(payload = claims + ("metadata" to JsonObject(
            mapOf("openid_relying_party" to JsonObject(emptyMap()))))), 1_700_000_000L, 0).ok)
    }

    @Test
    fun federationEntityMetadataCannotCarryProtocolKeyLocations() {
        for (claim in listOf("jwks", "jwks_uri", "signed_jwks_uri")) {
            val metadata = JsonObject(mapOf("federation_entity" to JsonObject(mapOf(claim to JsonPrimitive("https://keys.example")))))
            assertFalse(EntityStatementValidation.validateStructure(jwt(payload = baseClaims("https://entity.example", "https://entity.example",
                mapOf("metadata" to metadata))), 1_700_000_000L, 0).ok)
        }
    }

    @Test
    fun permitsStandardDefaultValueDeletionAndUnknownNoncriticalExtensions() {
        val policy = JsonObject(mapOf("openid_provider" to JsonObject(mapOf(
            "organization_name" to JsonObject(mapOf("default" to JsonPrimitive("Example"))),
            "logo_uri" to JsonObject(mapOf("value" to JsonNull)),
            "vendor" to JsonObject(mapOf("vendor_operator" to JsonObject(emptyMap()))),
        ))))
        assertTrue(EntityStatementValidation.validateStructure(jwt(payload = baseClaims("https://superior.example", "https://entity.example",
            mapOf("metadata_policy" to policy, "vendor_claim" to JsonObject(emptyMap())))), 1_700_000_000L, 1).ok)
    }

    @Test
    fun rejectsMalformedTrustMarkClaimsAndMismatchedEmbeddedTypes() {
        val compactMark = compact("""{"alg":"ES256","kid":"key","typ":"trust-mark+jwt"}""",
            """{"trust_mark_type":"https://marks.example/actual"}""")
        val cases = listOf(
            "trust_marks" to JsonPrimitive("invalid"),
            "trust_marks" to JsonArray(listOf(JsonObject(mapOf("trust_mark_type" to JsonPrimitive("https://marks.example/other"), "trust_mark" to JsonPrimitive(compactMark))))),
            "trust_mark_issuers" to JsonObject(mapOf("mark" to JsonArray(listOf(JsonPrimitive("http://issuer.example"))))),
            "trust_mark_owners" to JsonObject(mapOf("mark" to JsonObject(mapOf("sub" to JsonPrimitive("https://owner.example"))))),
        )
        for ((claim, value) in cases) assertFalse(EntityStatementValidation.validateStructure(
            jwt(payload = baseClaims("https://entity.example", "https://entity.example", mapOf(claim to value))),
            1_700_000_000L, 0).ok, "$claim: $value")
        assertTrue(EntityStatementValidation.validateStructure(jwt(payload = baseClaims("https://entity.example", "https://entity.example",
            mapOf("trust_marks" to JsonArray(listOf(JsonObject(mapOf("trust_mark_type" to JsonPrimitive("https://marks.example/actual"), "trust_mark" to JsonPrimitive(compactMark)))))))),
            1_700_000_000L, 0).ok)
    }

    @Test
    fun rejectsMalformedPoliciesCriticalOperatorsAndSourceEndpoints() {
        val cases = listOf(
            "metadata_policy" to JsonArray(emptyList()),
            "metadata_policy" to JsonObject(mapOf("openid_provider" to JsonObject(mapOf("scopes_supported" to JsonObject(mapOf("subset_of" to JsonPrimitive("openid"))))))),
            "metadata_policy" to JsonObject(mapOf("openid_provider" to JsonObject(mapOf("scopes_supported" to JsonObject(mapOf("essential" to JsonPrimitive("true"))))))),
            "metadata_policy_crit" to JsonArray(listOf(JsonPrimitive("subset_of"))),
            "metadata_policy_crit" to JsonArray(listOf(JsonPrimitive("unknown_operator"))),
            "metadata_policy_crit" to JsonNull,
            "source_endpoint" to JsonPrimitive("http://superior.example/fetch"),
            "source_endpoint" to JsonObject(emptyMap()),
        )
        for ((claim, value) in cases) assertFalse(EntityStatementValidation.validateStructure(
            jwt(payload = baseClaims("https://superior.example", "https://entity.example", mapOf(claim to value))),
            1_700_000_000L, 1).ok, "$claim: $value")
        assertTrue(EntityStatementValidation.validateStructure(jwt(payload = baseClaims("https://superior.example", "https://entity.example",
            mapOf("source_endpoint" to JsonPrimitive("https://superior.example/fetch?version=2"), "metadata_policy" to JsonObject(mapOf(
                "openid_provider" to JsonObject(mapOf("scopes_supported" to JsonObject(mapOf("subset_of" to JsonArray(listOf(JsonPrimitive("openid")))))))))))),
            1_700_000_000L, 1).ok)
    }

    @Test
    fun rejectsInvalidEntityIdentifiersAndDiscoveryHints() {
        for (invalid in listOf("http://entity.example", "https://entity.example?query=1", "https://entity.example#fragment", "urn:entity:example")) {
            for (claim in listOf("iss", "sub", "authority_hints", "trust_anchor_hints")) {
                val value = if (claim.endsWith("hints")) JsonArray(listOf(JsonPrimitive(invalid))) else JsonPrimitive(invalid)
                val result = EntityStatementValidation.validateStructure(
                    jwt(payload = baseClaims("https://entity.example", "https://entity.example", mapOf(claim to value))),
                    1_700_000_000L, 0,
                )
                assertFalse(result.ok, "$claim: $invalid")
            }
        }
    }

    @Test
    fun rejectsMalformedJwkSetsAndDuplicateKeyIdentifiers() {
        val key = JsonObject(mapOf("kid" to JsonPrimitive("key"), "kty" to JsonPrimitive("EC")))
        for (invalid in listOf(JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()),
            JsonObject(mapOf("keys" to JsonArray(listOf(JsonPrimitive("key"))))),
            JsonObject(mapOf("keys" to JsonArray(listOf(JsonObject(mapOf("kid" to JsonPrimitive(1))))))),
            JsonObject(mapOf("keys" to JsonArray(listOf(key, key)))))) {
            val result = EntityStatementValidation.validateStructure(
                jwt(payload = baseClaims("https://entity.example", "https://entity.example", mapOf("jwks" to invalid))),
                1_700_000_000L, 0,
            )
            assertFalse(result.ok, invalid.toString())
        }
    }

    @Test
    fun rejectsMalformedMetadataRolesAndNullParameterValues() {
        for (invalid in listOf(JsonNull, JsonArray(emptyList()),
            JsonObject(mapOf("federation_entity" to JsonPrimitive("invalid"))),
            JsonObject(mapOf("federation_entity" to JsonObject(mapOf("organization_name" to JsonNull)))))) {
            val result = EntityStatementValidation.validateStructure(
                jwt(payload = baseClaims("https://entity.example", "https://entity.example", mapOf("metadata" to invalid))),
                1_700_000_000L, 0,
            )
            assertFalse(result.ok, invalid.toString())
        }
        assertTrue(EntityStatementValidation.validateStructure(
            jwt(payload = baseClaims("https://entity.example", "https://entity.example", mapOf("metadata" to
                JsonObject(mapOf("federation_entity" to JsonObject(emptyMap())))))), 1_700_000_000L, 0).ok)
    }

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
            "jwks" to JsonObject(mapOf("keys" to JsonArray(emptyList()))),
            "metadata" to JsonObject(mapOf("federation_entity" to JsonObject(emptyMap()))),
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
    fun rejectsNonStringIdentifiersAndMalformedHintEntriesWithoutThrowing() {
        val now = 1_700_000_000L
        val malformedIdentifiers = listOf(
            JsonPrimitive(7), JsonPrimitive(true), JsonObject(emptyMap()), JsonArray(emptyList()),
        )
        for (claim in listOf("iss", "sub")) {
            for (value in malformedIdentifiers) {
                val claims = baseClaims("https://leaf", "https://leaf") + (claim to value)
                val result = runCatching {
                    EntityStatementValidation.validateStructure(jwt(payload = claims), now, 0)
                }.getOrThrow()
                assertFalse(result.ok, "$claim must be a string: $value")
            }
        }

        for (claim in listOf("authority_hints", "trust_anchor_hints")) {
            for (value in malformedIdentifiers) {
                val result = runCatching {
                    EntityStatementValidation.validateStructure(jwt(payload = baseClaims(
                        "https://leaf", "https://leaf", mapOf(claim to JsonArray(listOf(value))),
                    )), now, 0)
                }.getOrThrow()
                assertFalse(result.ok, "$claim entries must be strings: $value")
            }
        }
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
