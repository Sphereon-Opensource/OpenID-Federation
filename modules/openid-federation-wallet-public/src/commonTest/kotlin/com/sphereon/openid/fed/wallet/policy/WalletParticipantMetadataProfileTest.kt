package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

/** Draft-05 issuer/verifier participant metadata, independent of discovery and request handling. */
class WalletParticipantMetadataProfileTest {
    private val issuerId = "https://issuer.example"
    private val verifierId = "https://verifier.example"

    // Public P-256 coordinates from RFC 7517 §3; no private member or placeholder {kty,kid} key.
    private val publicJwk = obj(
        """{"kty":"EC","crv":"P-256","x":"f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU","y":"x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0","kid":"issuer-key-1","use":"sig","alg":"ES256"}"""
    )

    private fun issuerBaseline(): JsonObject = obj(
        """{
          "federation_entity":{"organization_name":"Issuer"},
          "openid_credential_issuer":{
            "credential_issuer":"https://issuer.example",
            "credential_endpoint":"https://issuer.example/credential",
            "credential_configurations_supported":{
              "IdentityCredential":{
                "format":"dc+sd-jwt",
                "vct":"https://credentials.example/identity",
                "cryptographic_binding_methods_supported":["jwk"],
                "credential_signing_alg_values_supported":["ES256"],
                "proof_types_supported":{"jwt":{"proof_signing_alg_values_supported":["ES256"]}}
              }
            },
            "jwks":{"keys":[$publicJwk]}
          }
        }"""
    )

    private fun verifierBaseline(): JsonObject = obj(
        """{
          "federation_entity":{"organization_name":"Verifier"},
          "openid_credential_verifier":{
            "vp_formats_supported":{
              "dc+sd-jwt":{"sd-jwt_alg_values":["ES256"],"kb-jwt_alg_values":["ES256"]}
            }
          }
        }"""
    )

    @Test
    fun completeCredentialIssuerBaselineAcceptsRealPublicKeyAndCredentialConfiguration() {
        val metadata = issuerBaseline()
        assertValid(metadata, issuerId, WalletEntityTypes.OPENID_CREDENTIAL_ISSUER)
    }

    @Test
    fun optionalOrganizationAndDisplayNamesDoNotBlockCompleteIssuerOrVerifier() {
        assertValid(issuerBaseline(), issuerId, WalletEntityTypes.OPENID_CREDENTIAL_ISSUER)
        assertValid(verifierBaseline(), verifierId, WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER)
        val issuerWithoutNames = JsonObject(issuerBaseline() + ("federation_entity" to obj("{}")))
        val verifierWithoutNames = JsonObject(verifierBaseline() + ("federation_entity" to obj("{}")))

        assertValid(issuerWithoutNames, issuerId, WalletEntityTypes.OPENID_CREDENTIAL_ISSUER)
        assertValid(verifierWithoutNames, verifierId, WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER)
    }

    @Test
    fun omittedOptionalIssuerJwksNeedsNoVcIssuerFallback() {
        assertValid(issuerBaseline(), issuerId, WalletEntityTypes.OPENID_CREDENTIAL_ISSUER)
        val issuer = issuerBaseline()["openid_credential_issuer"]!!.jsonObject
        val withoutKeys = JsonObject(issuerBaseline() + ("openid_credential_issuer" to JsonObject(issuer - "jwks")))
        assertTrue("vc_issuer" !in withoutKeys)

        assertValid(withoutKeys, issuerId, WalletEntityTypes.OPENID_CREDENTIAL_ISSUER)
    }

    @Test
    fun missingOrWronglyTypedRequiredIssuerFieldsAreRejectedFromPassingBaseline() {
        val baseline = issuerBaseline()
        assertValid(baseline, issuerId, WalletEntityTypes.OPENID_CREDENTIAL_ISSUER)
        val issuer = baseline["openid_credential_issuer"]!!.jsonObject
        for (field in listOf("credential_issuer", "credential_endpoint", "credential_configurations_supported")) {
            assertInvalid(withIssuer(baseline, JsonObject(issuer - field)), issuerId, WalletEntityTypes.OPENID_CREDENTIAL_ISSUER)
            val wrongType = if (field == "credential_configurations_supported") json("[]") else json("{}")
            assertInvalid(
                withIssuer(baseline, JsonObject(issuer + (field to wrongType))),
                issuerId, WalletEntityTypes.OPENID_CREDENTIAL_ISSUER,
            )
        }
    }

    @Test
    fun issuerIdentifiersRequireExactHttpsAndCredentialEndpointRequiresHttps() {
        val baseline = issuerBaseline()
        assertValid(baseline, issuerId, WalletEntityTypes.OPENID_CREDENTIAL_ISSUER)
        for (nearMatch in listOf("https://issuer.example/", "https://Issuer.example")) {
            assertInvalid(withIssuerField(baseline, "credential_issuer", json("\"$nearMatch\"")), issuerId,
                WalletEntityTypes.OPENID_CREDENTIAL_ISSUER)
        }
        val httpId = "http://issuer.example"
        assertInvalid(
            withIssuerField(baseline, "credential_issuer", json("\"$httpId\"")),
            httpId, WalletEntityTypes.OPENID_CREDENTIAL_ISSUER,
        )
        assertInvalid(
            withIssuerField(baseline, "credential_endpoint", json("\"http://issuer.example/credential\"")),
            issuerId, WalletEntityTypes.OPENID_CREDENTIAL_ISSUER,
        )
    }

    @Test
    fun authorizationServersMustBeIndependentHttpsEntityIdentifiersWhenPresent() {
        val baseline = issuerBaseline()
        assertValid(baseline, issuerId, WalletEntityTypes.OPENID_CREDENTIAL_ISSUER)
        val valid = withIssuerField(
            baseline, "authorization_servers", json("""["https://as-one.example","https://as-two.example"]""")
        )
        assertValid(valid, issuerId, WalletEntityTypes.OPENID_CREDENTIAL_ISSUER)
        for (bad in listOf("null", "[]", "\"https://as-one.example\"", "[7]", "[true]", "[{}]", "[\"http://as.example\"]")) {
            assertInvalid(
                withIssuerField(baseline, "authorization_servers", json(bad)),
                issuerId, WalletEntityTypes.OPENID_CREDENTIAL_ISSUER,
            )
        }
    }

    @Test
    fun malformedPresentIssuerJwksDuplicateKidsAndPrivateKeysAreRejected() {
        val baseline = issuerBaseline()
        assertValid(baseline, issuerId, WalletEntityTypes.OPENID_CREDENTIAL_ISSUER)
        val invalidJwks = listOf(
            "null", "{}", "{\"keys\":[]}", "{\"keys\":[{\"kty\":\"EC\",\"kid\":\"incomplete\"}]}",
            """{"keys":[$publicJwk,$publicJwk]}""",
            """{"keys":[${JsonObject(publicJwk + ("d" to json("\"870MB6gfuTJ4HtUnUvYMyJpr5eUZNP4Bk43bVdj3eAE\"")))}]}""",
        )
        for (bad in invalidJwks) {
            assertInvalid(withIssuerField(baseline, "jwks", json(bad)), issuerId, WalletEntityTypes.OPENID_CREDENTIAL_ISSUER)
        }
    }

    @Test
    fun completeVerifierFormatsAcceptAbsentOptionalUriListsAndJwks() {
        val baseline = verifierBaseline()
        assertValid(baseline, verifierId, WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER)
        val verifier = baseline["openid_credential_verifier"]!!.jsonObject
        assertTrue("request_uris" !in verifier && "response_uris" !in verifier && "redirect_uris" !in verifier)
        assertTrue("jwks" !in verifier)
    }

    @Test
    fun missingMalformedOrEmptyVerifierFormatsAreRejected() {
        val baseline = verifierBaseline()
        assertValid(baseline, verifierId, WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER)
        val verifier = baseline["openid_credential_verifier"]!!.jsonObject
        assertInvalid(withVerifier(baseline, JsonObject(verifier - "vp_formats_supported")), verifierId,
            WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER)
        for (bad in listOf("null", "[]", "{}", "{\"dc+sd-jwt\":{}}", "{\"dc+sd-jwt\":{\"sd-jwt_alg_values\":[]}}")) {
            assertInvalid(withVerifierField(baseline, "vp_formats_supported", json(bad)), verifierId,
                WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER)
        }
    }

    @Test
    fun presentVerifierUriListsRejectEmptyWrongTypesAndNonHttpsMembers() {
        val baseline = verifierBaseline()
        assertValid(baseline, verifierId, WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER)
        val invalid = listOf(
            "null", "[]", "\"https://verifier.example/endpoint\"", "[7]", "[true]",
            "[[\"https://verifier.example/endpoint\"]]", "[\"http://verifier.example/endpoint\"]", "[null]",
        )
        for (field in listOf("request_uris", "response_uris", "redirect_uris")) {
            for (bad in invalid) {
                assertInvalid(withVerifierField(baseline, field, json(bad)), verifierId,
                    WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER)
            }
        }
    }

    @Test
    fun presentHttpsVerifierUriListsAndUnknownExtensionAreAccepted() {
        val baseline = verifierBaseline()
        assertValid(baseline, verifierId, WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER)
        var metadata = baseline
        for (field in listOf("request_uris", "response_uris", "redirect_uris")) {
            metadata = withVerifierField(metadata, field, json("""["https://verifier.example/$field"]"""))
        }
        val extension = obj("""{"nested":[1,true,"opaque"]}""")
        metadata = withVerifierField(metadata, "vendor_extension", extension)

        assertValid(metadata, verifierId, WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER)
    }

    @Test
    fun malformedPresentVerifierJwksDuplicateKidsAndPrivateKeysAreRejected() {
        val baseline = verifierBaseline()
        assertValid(baseline, verifierId, WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER)
        val invalidJwks = listOf(
            "null", "{}", "{\"keys\":[]}", "{\"keys\":[{\"kty\":\"EC\",\"kid\":\"incomplete\"}]}",
            """{"keys":[$publicJwk,$publicJwk]}""",
            """{"keys":[${JsonObject(publicJwk + ("d" to json("\"870MB6gfuTJ4HtUnUvYMyJpr5eUZNP4Bk43bVdj3eAE\"")))}]}""",
        )
        for (bad in invalidJwks) {
            assertInvalid(withVerifierField(baseline, "jwks", json(bad)), verifierId,
                WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER)
        }
    }

    private fun withIssuer(baseline: JsonObject, issuer: JsonObject): JsonObject =
        JsonObject(baseline + ("openid_credential_issuer" to issuer))

    private fun withIssuerField(baseline: JsonObject, field: String, value: JsonElement): JsonObject =
        withIssuer(baseline, JsonObject(baseline["openid_credential_issuer"]!!.jsonObject + (field to value)))

    private fun withVerifier(baseline: JsonObject, verifier: JsonObject): JsonObject =
        JsonObject(baseline + ("openid_credential_verifier" to verifier))

    private fun withVerifierField(baseline: JsonObject, field: String, value: JsonElement): JsonObject =
        withVerifier(baseline, JsonObject(baseline["openid_credential_verifier"]!!.jsonObject + (field to value)))

    private fun assertValid(metadata: JsonObject, entityId: String, role: String) {
        val checks = runCatching { WalletProfileValidator.validate(metadata, entityId, role) }
        assertTrue(checks.isSuccess, "valid $role metadata must not throw: ${checks.exceptionOrNull()}")
        assertTrue(checks.getOrThrow().all { it.passed }, "valid $role metadata rejected: ${checks.getOrThrow()}")
    }

    private fun assertInvalid(metadata: JsonObject, entityId: String, role: String) {
        val checks = runCatching { WalletProfileValidator.validate(metadata, entityId, role) }
        assertTrue(checks.isSuccess, "malformed $role metadata must return controlled checks: ${checks.exceptionOrNull()}")
        assertTrue(checks.getOrThrow().any { !it.passed }, "malformed $role metadata accepted: $metadata")
    }

    private fun json(value: String): JsonElement = Json.parseToJsonElement(value)
    private fun obj(value: String): JsonObject = json(value).jsonObject
}
