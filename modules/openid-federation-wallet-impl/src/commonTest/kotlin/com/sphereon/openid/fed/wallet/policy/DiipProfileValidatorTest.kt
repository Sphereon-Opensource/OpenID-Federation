package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiipProfileValidatorTest {

    // =========================================================================
    // validate - display_name checks
    // =========================================================================

    @Test
    fun testDisplayNamePresent() {
        val metadata = JsonObject(mapOf(
            "federation_entity" to JsonObject(mapOf(
                "display_name" to JsonPrimitive("My Issuer")
            ))
        ))

        val results = DiipProfileValidator.validate(metadata, "https://issuer.example")
        val displayNameCheck = results.first { it.check == "federation_entity.display_name" }
        assertTrue(displayNameCheck.passed)
    }

    @Test
    fun testDisplayNameMissing() {
        val metadata = JsonObject(mapOf(
            "federation_entity" to JsonObject(emptyMap())
        ))

        val results = DiipProfileValidator.validate(metadata, "https://issuer.example")
        val displayNameCheck = results.first { it.check == "federation_entity.display_name" }
        assertFalse(displayNameCheck.passed)
        assertTrue(displayNameCheck.detail!!.contains("display_name"))
    }

    @Test
    fun testDisplayNameBlank() {
        val metadata = JsonObject(mapOf(
            "federation_entity" to JsonObject(mapOf(
                "display_name" to JsonPrimitive("")
            ))
        ))

        val results = DiipProfileValidator.validate(metadata, "https://issuer.example")
        val displayNameCheck = results.first { it.check == "federation_entity.display_name" }
        assertFalse(displayNameCheck.passed)
    }

    @Test
    fun testNoFederationEntityMetadata() {
        val metadata = JsonObject(emptyMap())

        val results = DiipProfileValidator.validate(metadata, "https://issuer.example")
        val displayNameCheck = results.first { it.check == "federation_entity.display_name" }
        assertFalse(displayNameCheck.passed)
    }

    // =========================================================================
    // validate - credential issuer checks
    // =========================================================================

    @Test
    fun testIssuerAllChecksPassed() {
        val metadata = buildCompleteIssuerMetadata(
            entityIdentifier = "https://issuer.example",
            displayName = "My Issuer"
        )

        val results = DiipProfileValidator.validate(metadata, "https://issuer.example", "openid_credential_issuer")
        assertTrue(results.all { it.passed }, "All checks should pass, but failed: ${results.filter { !it.passed }}")
    }

    @Test
    fun testIssuerMissingOpenidCredentialIssuer() {
        val metadata = JsonObject(mapOf(
            "federation_entity" to JsonObject(mapOf(
                "display_name" to JsonPrimitive("My Issuer")
            )),
            "vc_issuer" to buildVcIssuerJwks()
        ))

        val results = DiipProfileValidator.validate(metadata, "https://issuer.example", "openid_credential_issuer")
        val issuerCheck = results.first { it.check == "openid_credential_issuer_present" }
        assertFalse(issuerCheck.passed)
    }

    @Test
    fun testIssuerCredentialIssuerMismatch() {
        val metadata = JsonObject(mapOf(
            "federation_entity" to JsonObject(mapOf(
                "display_name" to JsonPrimitive("My Issuer")
            )),
            "openid_credential_issuer" to JsonObject(mapOf(
                "credential_issuer" to JsonPrimitive("https://wrong.example")
            )),
            "vc_issuer" to buildVcIssuerJwks()
        ))

        val results = DiipProfileValidator.validate(metadata, "https://issuer.example", "openid_credential_issuer")
        val matchCheck = results.first { it.check == "credential_issuer_matches_entity_id" }
        assertFalse(matchCheck.passed)
        assertTrue(matchCheck.detail!!.contains("does not match"))
    }

    @Test
    fun testIssuerCredentialIssuerMatches() {
        val metadata = buildCompleteIssuerMetadata(
            entityIdentifier = "https://issuer.example",
            displayName = "My Issuer"
        )

        val results = DiipProfileValidator.validate(metadata, "https://issuer.example", "openid_credential_issuer")
        val matchCheck = results.first { it.check == "credential_issuer_matches_entity_id" }
        assertTrue(matchCheck.passed)
    }

    @Test
    fun testIssuerMissingVcIssuerKeys() {
        val metadata = JsonObject(mapOf(
            "federation_entity" to JsonObject(mapOf(
                "display_name" to JsonPrimitive("My Issuer")
            )),
            "openid_credential_issuer" to JsonObject(mapOf(
                "credential_issuer" to JsonPrimitive("https://issuer.example")
            ))
            // No vc_issuer
        ))

        val results = DiipProfileValidator.validate(metadata, "https://issuer.example", "openid_credential_issuer")
        val keysCheck = results.first { it.check == "vc_issuer_signing_keys" }
        assertFalse(keysCheck.passed)
    }

    @Test
    fun testIssuerVcIssuerHasSigningKeys() {
        val metadata = buildCompleteIssuerMetadata(
            entityIdentifier = "https://issuer.example",
            displayName = "My Issuer"
        )

        val results = DiipProfileValidator.validate(metadata, "https://issuer.example", "openid_credential_issuer")
        val keysCheck = results.first { it.check == "vc_issuer_signing_keys" }
        assertTrue(keysCheck.passed)
    }

    @Test
    fun testIssuerVcIssuerNoSigUse() {
        val metadata = JsonObject(mapOf(
            "federation_entity" to JsonObject(mapOf(
                "display_name" to JsonPrimitive("My Issuer")
            )),
            "openid_credential_issuer" to JsonObject(mapOf(
                "credential_issuer" to JsonPrimitive("https://issuer.example")
            )),
            "vc_issuer" to JsonObject(mapOf(
                "jwks" to JsonObject(mapOf(
                    "keys" to JsonArray(listOf(
                        JsonObject(mapOf(
                            "kid" to JsonPrimitive("key-1"),
                            "kty" to JsonPrimitive("EC"),
                            "use" to JsonPrimitive("enc") // encryption, not signing
                        ))
                    ))
                ))
            ))
        ))

        val results = DiipProfileValidator.validate(metadata, "https://issuer.example", "openid_credential_issuer")
        val sigCheck = results.first { it.check == "vc_issuer_has_sig_key" }
        assertFalse(sigCheck.passed)
    }

    // =========================================================================
    // validate - credential verifier checks
    // =========================================================================

    @Test
    fun testVerifierPresent() {
        val metadata = JsonObject(mapOf(
            "federation_entity" to JsonObject(mapOf(
                "display_name" to JsonPrimitive("My Verifier")
            )),
            "openid_credential_verifier" to JsonObject(emptyMap())
        ))

        val results = DiipProfileValidator.validate(metadata, "https://verifier.example", "openid_credential_verifier")
        val verifierCheck = results.first { it.check == "openid_credential_verifier_present" }
        assertTrue(verifierCheck.passed)
    }

    @Test
    fun testVerifierMissing() {
        val metadata = JsonObject(mapOf(
            "federation_entity" to JsonObject(mapOf(
                "display_name" to JsonPrimitive("My Verifier")
            ))
        ))

        val results = DiipProfileValidator.validate(metadata, "https://verifier.example", "openid_credential_verifier")
        val verifierCheck = results.first { it.check == "openid_credential_verifier_present" }
        assertFalse(verifierCheck.passed)
    }

    // =========================================================================
    // validate - no entity type (generic)
    // =========================================================================

    @Test
    fun testNoEntityTypeOnlyChecksDisplayName() {
        val metadata = JsonObject(mapOf(
            "federation_entity" to JsonObject(mapOf(
                "display_name" to JsonPrimitive("My Entity")
            ))
        ))

        val results = DiipProfileValidator.validate(metadata, "https://entity.example")
        assertEquals(1, results.size)
        assertEquals("federation_entity.display_name", results[0].check)
        assertTrue(results[0].passed)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun buildVcIssuerJwks(): JsonObject {
        return JsonObject(mapOf(
            "jwks" to JsonObject(mapOf(
                "keys" to JsonArray(listOf(
                    JsonObject(mapOf(
                        "kid" to JsonPrimitive("key-1"),
                        "kty" to JsonPrimitive("EC"),
                        "alg" to JsonPrimitive("ES256"),
                        "crv" to JsonPrimitive("P-256"),
                        "use" to JsonPrimitive("sig"),
                        "x" to JsonPrimitive("dGhpcyBpcyBhIHRlc3Q"),
                        "y" to JsonPrimitive("dGhpcyBpcyBhIHRlc3Q")
                    ))
                ))
            ))
        ))
    }

    private fun buildCompleteIssuerMetadata(entityIdentifier: String, displayName: String): JsonObject {
        return JsonObject(mapOf(
            "federation_entity" to JsonObject(mapOf(
                "display_name" to JsonPrimitive(displayName)
            )),
            "openid_credential_issuer" to JsonObject(mapOf(
                "credential_issuer" to JsonPrimitive(entityIdentifier),
                "credential_endpoint" to JsonPrimitive("$entityIdentifier/credential")
            )),
            "vc_issuer" to buildVcIssuerJwks()
        ))
    }
}
