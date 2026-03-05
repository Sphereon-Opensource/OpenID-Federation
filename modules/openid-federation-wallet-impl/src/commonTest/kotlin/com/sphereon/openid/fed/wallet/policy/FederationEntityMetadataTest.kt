package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FederationEntityMetadataTest {

    // =========================================================================
    // extractIssuerIdentifier tests
    // =========================================================================

    @Test
    fun testExtractIssuerIdentifierFromFedClaim() {
        val payload = JsonObject(mapOf(
            "fed" to JsonPrimitive("https://credential-issuer.example"),
            "iss" to JsonPrimitive("https://other.example")
        ))

        val result = FederationEntityMetadata.extractIssuerIdentifier(payload)
        assertEquals("https://credential-issuer.example", result.identifier)
        assertEquals("fed", result.source)
        assertNull(result.error)
    }

    @Test
    fun testExtractIssuerIdentifierFallsBackToIss() {
        val payload = JsonObject(mapOf(
            "iss" to JsonPrimitive("https://credential-issuer.example")
        ))

        val result = FederationEntityMetadata.extractIssuerIdentifier(payload)
        assertEquals("https://credential-issuer.example", result.identifier)
        assertEquals("iss", result.source)
    }

    @Test
    fun testExtractIssuerIdentifierNoClaims() {
        val payload = JsonObject(mapOf(
            "sub" to JsonPrimitive("some-subject")
        ))

        val result = FederationEntityMetadata.extractIssuerIdentifier(payload)
        assertNull(result.identifier)
        assertEquals("none", result.source)
        assertTrue(result.error!!.contains("No 'fed' or 'iss' claim"))
    }

    @Test
    fun testExtractIssuerIdentifierEmptyPayload() {
        val payload = JsonObject(emptyMap())

        val result = FederationEntityMetadata.extractIssuerIdentifier(payload)
        assertNull(result.identifier)
    }

    // =========================================================================
    // validateFedClaim tests
    // =========================================================================

    @Test
    fun testValidateFedClaimMatches() {
        val payload = JsonObject(mapOf(
            "fed" to JsonPrimitive("https://credential-issuer.example")
        ))

        assertTrue(FederationEntityMetadata.validateFedClaim(payload, "https://credential-issuer.example"))
    }

    @Test
    fun testValidateFedClaimDoesNotMatch() {
        val payload = JsonObject(mapOf(
            "fed" to JsonPrimitive("https://credential-issuer.example")
        ))

        assertFalse(FederationEntityMetadata.validateFedClaim(payload, "https://other.example"))
    }

    @Test
    fun testValidateFedClaimMissing() {
        val payload = JsonObject(mapOf(
            "iss" to JsonPrimitive("https://credential-issuer.example")
        ))

        assertFalse(FederationEntityMetadata.validateFedClaim(payload, "https://credential-issuer.example"))
    }

    // =========================================================================
    // extractVcIssuerKeys tests
    // =========================================================================

    @Test
    fun testExtractVcIssuerKeys() {
        val metadata = buildVcIssuerMetadata(listOf(
            buildJsonKey("key-1", "EC", "ES256"),
            buildJsonKey("key-2", "EC", "ES256")
        ))

        val keys = FederationEntityMetadata.extractVcIssuerKeys(metadata)
        assertEquals(2, keys.size)
        assertEquals("key-1", keys[0]["kid"]?.jsonPrimitive?.content)
        assertEquals("key-2", keys[1]["kid"]?.jsonPrimitive?.content)
    }

    @Test
    fun testExtractVcIssuerKeysNoVcIssuer() {
        val metadata = JsonObject(mapOf(
            "openid_credential_issuer" to JsonObject(emptyMap())
        ))

        val keys = FederationEntityMetadata.extractVcIssuerKeys(metadata)
        assertTrue(keys.isEmpty())
    }

    @Test
    fun testExtractVcIssuerKeysNoJwks() {
        val metadata = JsonObject(mapOf(
            "vc_issuer" to JsonObject(emptyMap())
        ))

        val keys = FederationEntityMetadata.extractVcIssuerKeys(metadata)
        assertTrue(keys.isEmpty())
    }

    @Test
    fun testExtractVcIssuerKeysEmptyKeys() {
        val metadata = JsonObject(mapOf(
            "vc_issuer" to JsonObject(mapOf(
                "jwks" to JsonObject(mapOf(
                    "keys" to JsonArray(emptyList())
                ))
            ))
        ))

        val keys = FederationEntityMetadata.extractVcIssuerKeys(metadata)
        assertTrue(keys.isEmpty())
    }

    // =========================================================================
    // findVcIssuerKey tests
    // =========================================================================

    @Test
    fun testFindVcIssuerKeyFound() {
        val metadata = buildVcIssuerMetadata(listOf(
            buildJsonKey("key-1", "EC", "ES256"),
            buildJsonKey("key-2", "EC", "ES256")
        ))

        val key = FederationEntityMetadata.findVcIssuerKey(metadata, "key-2")
        assertEquals("key-2", key?.get("kid")?.jsonPrimitive?.content)
    }

    @Test
    fun testFindVcIssuerKeyNotFound() {
        val metadata = buildVcIssuerMetadata(listOf(
            buildJsonKey("key-1", "EC", "ES256")
        ))

        val key = FederationEntityMetadata.findVcIssuerKey(metadata, "key-999")
        assertNull(key)
    }

    @Test
    fun testFindVcIssuerKeyTrimsWhitespace() {
        val metadata = buildVcIssuerMetadata(listOf(
            buildJsonKey("key-1 ", "EC", "ES256")
        ))

        val key = FederationEntityMetadata.findVcIssuerKey(metadata, " key-1")
        assertEquals("key-1 ", key?.get("kid")?.jsonPrimitive?.content)
    }

    // =========================================================================
    // getDisplayName tests
    // =========================================================================

    @Test
    fun testGetDisplayName() {
        val metadata = JsonObject(mapOf(
            "federation_entity" to JsonObject(mapOf(
                "display_name" to JsonPrimitive("My Entity")
            ))
        ))

        assertEquals("My Entity", FederationEntityMetadata.getDisplayName(metadata))
    }

    @Test
    fun testGetDisplayNameMissing() {
        val metadata = JsonObject(mapOf(
            "federation_entity" to JsonObject(emptyMap())
        ))

        assertNull(FederationEntityMetadata.getDisplayName(metadata))
    }

    @Test
    fun testGetDisplayNameNoFederationEntity() {
        val metadata = JsonObject(emptyMap())
        assertNull(FederationEntityMetadata.getDisplayName(metadata))
    }

    // =========================================================================
    // hasFederationIssuerMetadata tests
    // =========================================================================

    @Test
    fun testHasFederationIssuerMetadataTrue() {
        val metadata = JsonObject(mapOf(
            "openid_credential_issuer" to JsonObject(emptyMap())
        ))

        assertTrue(FederationEntityMetadata.hasFederationIssuerMetadata(metadata))
    }

    @Test
    fun testHasFederationIssuerMetadataFalse() {
        val metadata = JsonObject(mapOf(
            "federation_entity" to JsonObject(emptyMap())
        ))

        assertFalse(FederationEntityMetadata.hasFederationIssuerMetadata(metadata))
    }

    // =========================================================================
    // getEntityTypeMetadata tests
    // =========================================================================

    @Test
    fun testGetEntityTypeMetadata() {
        val issuerMeta = JsonObject(mapOf("credential_issuer" to JsonPrimitive("https://issuer.example")))
        val metadata = JsonObject(mapOf(
            "openid_credential_issuer" to issuerMeta
        ))

        assertEquals(issuerMeta, FederationEntityMetadata.getEntityTypeMetadata(metadata, "openid_credential_issuer"))
    }

    @Test
    fun testGetEntityTypeMetadataMissing() {
        val metadata = JsonObject(emptyMap())
        assertNull(FederationEntityMetadata.getEntityTypeMetadata(metadata, "openid_credential_issuer"))
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun buildJsonKey(kid: String, kty: String, alg: String): JsonObject {
        return JsonObject(mapOf(
            "kid" to JsonPrimitive(kid),
            "kty" to JsonPrimitive(kty),
            "alg" to JsonPrimitive(alg),
            "use" to JsonPrimitive("sig")
        ))
    }

    private fun buildVcIssuerMetadata(keys: List<JsonObject>): JsonObject {
        return JsonObject(mapOf(
            "vc_issuer" to JsonObject(mapOf(
                "jwks" to JsonObject(mapOf(
                    "keys" to JsonArray(keys)
                ))
            ))
        ))
    }
}
