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
    fun missingNameFails() {
        val metadata = JsonObject(
            mapOf("federation_entity" to JsonObject(emptyMap()))
        )
        val results = WalletProfileValidator.validate(metadata, "https://entity.example")
        assertFalse(results.first { it.check == "federation_entity.organization_name" }.passed)
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
    fun credentialIssuerAcceptsVcIssuerFallback() {
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
        assertTrue(keys.detail!!.contains("vc_issuer"))
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

    private fun sigKey(kid: String) = JsonObject(
        mapOf(
            "kid" to JsonPrimitive(kid),
            "kty" to JsonPrimitive("EC"),
            "use" to JsonPrimitive("sig"),
            "alg" to JsonPrimitive("ES256"),
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
                    "jwks" to JsonObject(
                        mapOf("keys" to JsonArray(listOf(sigKey("ci-1"))))
                    ),
                )
            ),
        )
    )
}
