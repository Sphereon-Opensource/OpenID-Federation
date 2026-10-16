package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletProfileValidatorTest {

    @Test
    fun organizationNamePreferred() {
        val metadata = JsonObject(
            mapOf(
                "federation_entity" to JsonObject(
                    mapOf("organization_name" to JsonPrimitive("Org"))
                )
            )
        )
        val results = WalletProfileValidator.validate(metadata, "https://entity.example")
        val name = results.first { it.check == "federation_entity.organization_name" }
        assertTrue(name.passed)
        assertEquals(MetadataValidationCheck.PROFILE_WALLET, name.profile)
    }

    @Test
    fun displayNameAcceptedAsDualProfileFallback() {
        val metadata = JsonObject(
            mapOf(
                "federation_entity" to JsonObject(
                    mapOf("display_name" to JsonPrimitive("Legacy"))
                )
            )
        )
        val results = WalletProfileValidator.validate(metadata, "https://entity.example")
        val name = results.first { it.check == "federation_entity.organization_name" }
        assertTrue(name.passed)
        assertTrue(name.detail!!.contains("display_name"))
    }

    @Test
    fun missingRecommendedNameDoesNotBlockProtocolValidation() {
        val metadata = JsonObject(
            mapOf("federation_entity" to JsonObject(emptyMap()))
        )
        val results = WalletProfileValidator.validate(metadata, "https://entity.example")
        assertTrue(results.first { it.check == "federation_entity.organization_name" }.passed)
    }

    @Test
    fun credentialIssuerWithOpenidCredentialIssuerJwks() {
        val metadata = buildWalletIssuerMetadata("https://issuer.example")
        val results = WalletProfileValidator.validate(
            metadata, "https://issuer.example", WalletEntityTypes.OPENID_CREDENTIAL_ISSUER
        )
        assertTrue(results.all { it.passed }, "failed: ${results.filter { !it.passed }}")
        assertTrue(results.any { it.check == "credential_issuer_signing_keys" && it.passed })
    }

    @Test
    fun credentialIssuerDoesNotSubstituteVcIssuerForOptionalProfileKeys() {
        val metadata = JsonObject(
            mapOf(
                "federation_entity" to JsonObject(
                    mapOf("organization_name" to JsonPrimitive("CI"))
                ),
                "openid_credential_issuer" to JsonObject(
                    mapOf("credential_issuer" to JsonPrimitive("https://issuer.example"))
                ),
                "vc_issuer" to JsonObject(
                    mapOf(
                        "jwks" to JsonObject(
                            mapOf("keys" to JsonArray(listOf(sigKey("k1"))))
                        )
                    )
                ),
            )
        )
        val results = WalletProfileValidator.validate(
            metadata, "https://issuer.example", WalletEntityTypes.OPENID_CREDENTIAL_ISSUER
        )
        val keys = results.first { it.check == "credential_issuer_signing_keys" }
        assertTrue(keys.passed)
        assertTrue(keys.detail!!.contains("not substituted"))
        assertTrue(results.any { !it.passed }, "Required credential endpoint/configurations are still absent")
    }

    @Test
    fun oauthAuthorizationServerValidation() {
        val metadata = JsonObject(
            mapOf(
                "federation_entity" to JsonObject(
                    mapOf("organization_name" to JsonPrimitive("AS"))
                ),
                "oauth_authorization_server" to JsonObject(
                    mapOf(
                        "issuer" to JsonPrimitive("https://as.example"),
                        "authorization_endpoint" to JsonPrimitive("https://as.example/auth"),
                        "token_endpoint" to JsonPrimitive("https://as.example/token"),
                    )
                ),
            )
        )
        val results = WalletProfileValidator.validate(
            metadata, "https://as.example", WalletEntityTypes.OAUTH_AUTHORIZATION_SERVER
        )
        assertTrue(results.all { it.passed }, "failed: ${results.filter { !it.passed }}")
        assertTrue(results.any { it.check == "oauth_authorization_server_present" })
        assertTrue(results.any { it.check == "oauth_authorization_server.issuer_matches_entity_id" })
    }

    @Test
    fun oauthAuthorizationServerIssuerMismatch() {
        val metadata = JsonObject(
            mapOf(
                "federation_entity" to JsonObject(
                    mapOf("organization_name" to JsonPrimitive("AS"))
                ),
                "oauth_authorization_server" to JsonObject(
                    mapOf(
                        "issuer" to JsonPrimitive("https://other.example"),
                        "token_endpoint" to JsonPrimitive("https://as.example/token"),
                    )
                ),
            )
        )
        val results = WalletProfileValidator.validate(
            metadata, "https://as.example", WalletEntityTypes.OAUTH_AUTHORIZATION_SERVER
        )
        val issuerCheck = results.first { it.check == "oauth_authorization_server.issuer_matches_entity_id" }
        assertFalse(issuerCheck.passed)
    }

    @Test
    fun walletProviderPresent() {
        val metadata = JsonObject(
            mapOf(
                "federation_entity" to JsonObject(
                    mapOf("organization_name" to JsonPrimitive("WP"))
                ),
                "openid_wallet_provider" to JsonObject(emptyMap()),
            )
        )
        val results = WalletProfileValidator.validate(
            metadata, "https://wp.example", WalletEntityTypes.OPENID_WALLET_PROVIDER
        )
        assertTrue(results.first { it.check == "openid_wallet_provider_present" }.passed)
    }

    @Test
    fun federationEntityPresent() {
        val metadata = JsonObject(
            mapOf(
                "federation_entity" to JsonObject(
                    mapOf("organization_name" to JsonPrimitive("TA"))
                )
            )
        )
        val results = WalletProfileValidator.validate(
            metadata, "https://ta.example", WalletEntityTypes.FEDERATION_ENTITY
        )
        assertTrue(results.first { it.check == "federation_entity_present" }.passed)
    }

    @Test
    fun credentialVerifierPresent() {
        val metadata = JsonObject(
            mapOf(
                "federation_entity" to JsonObject(
                    mapOf("organization_name" to JsonPrimitive("CV"))
                ),
                "openid_credential_verifier" to JsonObject(emptyMap()),
            )
        )
        val results = WalletProfileValidator.validate(
            metadata, "https://cv.example", WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER
        )
        assertTrue(results.first { it.check == "openid_credential_verifier_present" }.passed)
    }

    @Test
    fun verifierFormatAlgorithmsAreOptionalButMalformedPresentValuesFail() {
        fun formatsCheck(format: String, definition: JsonObject): Boolean {
            val metadata = JsonObject(mapOf("openid_credential_verifier" to JsonObject(mapOf(
                "vp_formats_supported" to JsonObject(mapOf(format to definition))))))
            return WalletProfileValidator.validate(metadata, "https://verifier.example",
                WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER).first { it.check == "vp_formats_supported" }.passed
        }
        for ((format, fields) in mapOf(
            "dc+sd-jwt" to listOf("sd-jwt_alg_values", "kb-jwt_alg_values"),
            "mso_mdoc" to listOf("issuerauth_alg_values", "deviceauth_alg_values"),
        )) {
            assertTrue(formatsCheck(format, JsonObject(emptyMap())))
            for (field in fields) {
                for (invalid in listOf(JsonPrimitive(true), JsonArray(emptyList()),
                    JsonArray(listOf(kotlinx.serialization.json.JsonNull)), JsonArray(listOf(JsonObject(emptyMap()))))) {
                    assertFalse(formatsCheck(format, JsonObject(mapOf(field to invalid))), "$format/$field: $invalid")
                }
                assertTrue(formatsCheck(format, JsonObject(mapOf(field to JsonArray(listOf(JsonPrimitive("ES256")))))))
                if (format == "mso_mdoc") {
                    assertTrue(formatsCheck(format, JsonObject(mapOf(field to JsonArray(listOf(JsonPrimitive(-7)))))))
                    assertFalse(formatsCheck(format, JsonObject(mapOf(field to JsonArray(listOf(JsonPrimitive(-7.5)))))))
                } else {
                    assertFalse(formatsCheck(format, JsonObject(mapOf(field to JsonArray(listOf(JsonPrimitive(-7)))))))
                }
            }
        }
    }

    @Test
    fun protocolKeyValidationDoesNotInterpretUnknownInlineKeysForOtherRoles() {
        for (role in listOf(WalletEntityTypes.OPENID_WALLET_PROVIDER, "vendor_participant")) {
            val metadata = JsonObject(mapOf(role to JsonObject(mapOf("jwks" to JsonPrimitive("vendor extension")))))
            assertTrue(WalletProfileValidator.validate(metadata, "https://provider.example", role).all { it.passed })
        }
        val metadata = JsonObject(mapOf(WalletEntityTypes.OAUTH_AUTHORIZATION_SERVER to JsonObject(mapOf(
            "issuer" to JsonPrimitive("https://as.example"), "jwks" to JsonPrimitive("vendor extension")))))
        assertTrue(WalletProfileValidator.validate(metadata, "https://as.example",
            WalletEntityTypes.OAUTH_AUTHORIZATION_SERVER).all { it.passed })
    }

    @Test
    fun issuerProtocolKeysDoNotInheritVerifierMandatoryKeyIdentifiers() {
        val baseline = buildWalletIssuerMetadata("https://issuer.example")
        val issuer = baseline[WalletEntityTypes.OPENID_CREDENTIAL_ISSUER] as JsonObject
        for (keys in listOf(JsonArray(listOf(JsonObject(sigKey("issuer") - "kid"))), JsonArray(emptyList()))) {
            val metadata = JsonObject(baseline + (WalletEntityTypes.OPENID_CREDENTIAL_ISSUER to
                JsonObject(issuer + ("jwks" to JsonObject(mapOf("keys" to keys))))))
            assertTrue(WalletProfileValidator.validate(metadata, "https://issuer.example",
                WalletEntityTypes.OPENID_CREDENTIAL_ISSUER).all { it.passed })
        }
        val verifier = JsonObject(mapOf(WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER to JsonObject(mapOf(
            "vp_formats_supported" to JsonObject(mapOf("mso_mdoc" to JsonObject(emptyMap()))),
            "jwks" to JsonObject(mapOf("keys" to JsonArray(listOf(JsonObject(sigKey("verifier") - "kid")))))))))
        assertFalse(WalletProfileValidator.validate(verifier, "https://verifier.example",
            WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER).first { it.check.endsWith(".jwks") }.passed)
    }

    private fun sigKey(kid: String) = JsonObject(
        mapOf(
            "kid" to JsonPrimitive(kid),
            "kty" to JsonPrimitive("EC"),
            "use" to JsonPrimitive("sig"),
            "alg" to JsonPrimitive("ES256"),
            "crv" to JsonPrimitive("P-256"),
            "x" to JsonPrimitive("f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU"),
            "y" to JsonPrimitive("x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"),
        )
    )

    private fun buildWalletIssuerMetadata(entityId: String) = JsonObject(
        mapOf(
            "federation_entity" to JsonObject(
                mapOf("organization_name" to JsonPrimitive("Issuer"))
            ),
            "openid_credential_issuer" to JsonObject(
                mapOf(
                    "credential_issuer" to JsonPrimitive(entityId),
                    "credential_endpoint" to JsonPrimitive("$entityId/credential"),
                    "credential_configurations_supported" to JsonObject(mapOf("Identity" to JsonObject(mapOf("format" to JsonPrimitive("dc+sd-jwt"))))),
                    "jwks" to JsonObject(
                        mapOf("keys" to JsonArray(listOf(sigKey("ci-1"))))
                    ),
                )
            ),
        )
    )
}
