package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetadataPolicyOperatorsTest {

    // =========================================================================
    // mergePolicies tests
    // =========================================================================

    @Test
    fun testMergePoliciesEmptyBase() {
        val base = JsonObject(emptyMap())
        val overlay = JsonObject(mapOf(
            "openid_credential_issuer" to JsonObject(mapOf(
                "credential_endpoint" to JsonObject(mapOf(
                    "essential" to JsonPrimitive(true)
                ))
            ))
        ))

        val result = MetadataPolicyOperators.mergePolicies(base, overlay)
        assertEquals(overlay, result)
    }

    @Test
    fun testMergePoliciesEmptyOverlay() {
        val base = JsonObject(mapOf(
            "scope" to JsonObject(mapOf(
                "default" to JsonPrimitive("openid")
            ))
        ))
        val overlay = JsonObject(emptyMap())

        val result = MetadataPolicyOperators.mergePolicies(base, overlay)
        assertEquals(base, result)
    }

    @Test
    fun testMergePoliciesOverlayOverrides() {
        val base = JsonObject(mapOf(
            "scope" to JsonObject(mapOf(
                "default" to JsonPrimitive("openid")
            ))
        ))
        val overlay = JsonObject(mapOf(
            "scope" to JsonObject(mapOf(
                "default" to JsonPrimitive("openid profile")
            ))
        ))

        val result = MetadataPolicyOperators.mergePolicies(base, overlay)
        val scopePolicy = result["scope"]!!.jsonObject
        assertEquals("openid profile", scopePolicy["default"]!!.jsonPrimitive.content)
    }

    @Test
    fun testMergePoliciesDeepMerge() {
        val base = JsonObject(mapOf(
            "openid_credential_issuer" to JsonObject(mapOf(
                "credential_endpoint" to JsonObject(mapOf(
                    "essential" to JsonPrimitive(true)
                ))
            ))
        ))
        val overlay = JsonObject(mapOf(
            "openid_credential_issuer" to JsonObject(mapOf(
                "authorization_endpoint" to JsonObject(mapOf(
                    "essential" to JsonPrimitive(true)
                ))
            ))
        ))

        val result = MetadataPolicyOperators.mergePolicies(base, overlay)
        val issuerPolicy = result["openid_credential_issuer"]!!.jsonObject
        assertTrue(issuerPolicy.containsKey("credential_endpoint"))
        assertTrue(issuerPolicy.containsKey("authorization_endpoint"))
    }

    @Test
    fun testMergePoliciesAddsNewKeys() {
        val base = JsonObject(mapOf(
            "key1" to JsonPrimitive("value1")
        ))
        val overlay = JsonObject(mapOf(
            "key2" to JsonPrimitive("value2")
        ))

        val result = MetadataPolicyOperators.mergePolicies(base, overlay)
        assertEquals("value1", result["key1"]!!.jsonPrimitive.content)
        assertEquals("value2", result["key2"]!!.jsonPrimitive.content)
    }

    // =========================================================================
    // applyPolicy - value operator tests
    // =========================================================================

    @Test
    fun testApplyPolicyValueOperatorOverrides() {
        val metadata = JsonObject(mapOf(
            "token_endpoint_auth_method" to JsonPrimitive("client_secret_basic")
        ))
        val policy = JsonObject(mapOf(
            "token_endpoint_auth_method" to JsonObject(mapOf(
                "value" to JsonPrimitive("private_key_jwt")
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        assertEquals("private_key_jwt", result.metadata["token_endpoint_auth_method"]!!.jsonPrimitive.content)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun testApplyPolicyValueOperatorSetsAbsentValue() {
        val metadata = JsonObject(emptyMap())
        val policy = JsonObject(mapOf(
            "token_endpoint_auth_method" to JsonObject(mapOf(
                "value" to JsonPrimitive("private_key_jwt")
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        assertEquals("private_key_jwt", result.metadata["token_endpoint_auth_method"]!!.jsonPrimitive.content)
    }

    // =========================================================================
    // applyPolicy - default operator tests
    // =========================================================================

    @Test
    fun testApplyPolicyDefaultOperatorSetsWhenAbsent() {
        val metadata = JsonObject(emptyMap())
        val policy = JsonObject(mapOf(
            "scope" to JsonObject(mapOf(
                "default" to JsonPrimitive("openid")
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        assertEquals("openid", result.metadata["scope"]!!.jsonPrimitive.content)
    }

    @Test
    fun testApplyPolicyDefaultOperatorDoesNotOverride() {
        val metadata = JsonObject(mapOf(
            "scope" to JsonPrimitive("openid profile")
        ))
        val policy = JsonObject(mapOf(
            "scope" to JsonObject(mapOf(
                "default" to JsonPrimitive("openid")
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        assertEquals("openid profile", result.metadata["scope"]!!.jsonPrimitive.content)
    }

    @Test
    fun testApplyPolicyDefaultOperatorSetsWhenNull() {
        val metadata = JsonObject(mapOf(
            "scope" to JsonNull
        ))
        val policy = JsonObject(mapOf(
            "scope" to JsonObject(mapOf(
                "default" to JsonPrimitive("openid")
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        assertEquals("openid", result.metadata["scope"]!!.jsonPrimitive.content)
    }

    // =========================================================================
    // applyPolicy - one_of operator tests
    // =========================================================================

    @Test
    fun testApplyPolicyOneOfValid() {
        val metadata = JsonObject(mapOf(
            "grant_types" to JsonPrimitive("authorization_code")
        ))
        val policy = JsonObject(mapOf(
            "grant_types" to JsonObject(mapOf(
                "one_of" to JsonArray(listOf(
                    JsonPrimitive("authorization_code"),
                    JsonPrimitive("implicit")
                ))
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun testApplyPolicyOneOfInvalid() {
        val metadata = JsonObject(mapOf(
            "grant_types" to JsonPrimitive("client_credentials")
        ))
        val policy = JsonObject(mapOf(
            "grant_types" to JsonObject(mapOf(
                "one_of" to JsonArray(listOf(
                    JsonPrimitive("authorization_code"),
                    JsonPrimitive("implicit")
                ))
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings[0].contains("grant_types"))
        assertTrue(result.warnings[0].contains("not one of"))
    }

    // =========================================================================
    // applyPolicy - subset_of operator tests
    // =========================================================================

    @Test
    fun testApplyPolicySubsetOfFilters() {
        val metadata = JsonObject(mapOf(
            "scopes_supported" to JsonArray(listOf(
                JsonPrimitive("openid"),
                JsonPrimitive("profile"),
                JsonPrimitive("email"),
                JsonPrimitive("phone")
            ))
        ))
        val policy = JsonObject(mapOf(
            "scopes_supported" to JsonObject(mapOf(
                "subset_of" to JsonArray(listOf(
                    JsonPrimitive("openid"),
                    JsonPrimitive("profile"),
                    JsonPrimitive("email")
                ))
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        val scopes = result.metadata["scopes_supported"]!!.jsonArray
        assertEquals(3, scopes.size)
        assertTrue(scopes.contains(JsonPrimitive("openid")))
        assertTrue(scopes.contains(JsonPrimitive("profile")))
        assertTrue(scopes.contains(JsonPrimitive("email")))
    }

    @Test
    fun testApplyPolicySubsetOfNoFiltering() {
        val metadata = JsonObject(mapOf(
            "scopes_supported" to JsonArray(listOf(
                JsonPrimitive("openid"),
                JsonPrimitive("profile")
            ))
        ))
        val policy = JsonObject(mapOf(
            "scopes_supported" to JsonObject(mapOf(
                "subset_of" to JsonArray(listOf(
                    JsonPrimitive("openid"),
                    JsonPrimitive("profile"),
                    JsonPrimitive("email")
                ))
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        val scopes = result.metadata["scopes_supported"]!!.jsonArray
        assertEquals(2, scopes.size)
    }

    // =========================================================================
    // applyPolicy - superset_of operator tests
    // =========================================================================

    @Test
    fun testApplyPolicySupersetOfValid() {
        val metadata = JsonObject(mapOf(
            "response_types_supported" to JsonArray(listOf(
                JsonPrimitive("code"),
                JsonPrimitive("id_token"),
                JsonPrimitive("vp_token")
            ))
        ))
        val policy = JsonObject(mapOf(
            "response_types_supported" to JsonObject(mapOf(
                "superset_of" to JsonArray(listOf(
                    JsonPrimitive("code"),
                    JsonPrimitive("vp_token")
                ))
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun testApplyPolicySupersetOfMissing() {
        val metadata = JsonObject(mapOf(
            "response_types_supported" to JsonArray(listOf(
                JsonPrimitive("code")
            ))
        ))
        val policy = JsonObject(mapOf(
            "response_types_supported" to JsonObject(mapOf(
                "superset_of" to JsonArray(listOf(
                    JsonPrimitive("code"),
                    JsonPrimitive("vp_token")
                ))
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings[0].contains("vp_token"))
    }

    // =========================================================================
    // applyPolicy - add operator tests
    // =========================================================================

    @Test
    fun testApplyPolicyAddToExistingArray() {
        val metadata = JsonObject(mapOf(
            "scopes_supported" to JsonArray(listOf(
                JsonPrimitive("openid")
            ))
        ))
        val policy = JsonObject(mapOf(
            "scopes_supported" to JsonObject(mapOf(
                "add" to JsonArray(listOf(
                    JsonPrimitive("profile"),
                    JsonPrimitive("email")
                ))
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        val scopes = result.metadata["scopes_supported"]!!.jsonArray
        assertEquals(3, scopes.size)
        assertTrue(scopes.contains(JsonPrimitive("openid")))
        assertTrue(scopes.contains(JsonPrimitive("profile")))
        assertTrue(scopes.contains(JsonPrimitive("email")))
    }

    @Test
    fun testApplyPolicyAddToAbsentCreatesArray() {
        val metadata = JsonObject(emptyMap())
        val policy = JsonObject(mapOf(
            "scopes_supported" to JsonObject(mapOf(
                "add" to JsonArray(listOf(
                    JsonPrimitive("openid")
                ))
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        val scopes = result.metadata["scopes_supported"]!!.jsonArray
        assertEquals(1, scopes.size)
        assertTrue(scopes.contains(JsonPrimitive("openid")))
    }

    // =========================================================================
    // applyPolicy - essential operator tests
    // =========================================================================

    @Test
    fun testApplyPolicyEssentialMissingWarns() {
        val metadata = JsonObject(emptyMap())
        val policy = JsonObject(mapOf(
            "credential_endpoint" to JsonObject(mapOf(
                "essential" to JsonPrimitive(true)
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings[0].contains("credential_endpoint"))
        assertTrue(result.warnings[0].contains("Essential"))
    }

    @Test
    fun testApplyPolicyEssentialPresentNoWarning() {
        val metadata = JsonObject(mapOf(
            "credential_endpoint" to JsonPrimitive("https://issuer.example.com/credential")
        ))
        val policy = JsonObject(mapOf(
            "credential_endpoint" to JsonObject(mapOf(
                "essential" to JsonPrimitive(true)
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        assertTrue(result.warnings.isEmpty())
    }

    // =========================================================================
    // applyPolicy - entityType scoping tests
    // =========================================================================

    @Test
    fun testApplyPolicyWithEntityType() {
        val metadata = JsonObject(mapOf(
            "openid_credential_issuer" to JsonObject(mapOf(
                "credential_endpoint" to JsonPrimitive("https://old.example.com/credential")
            )),
            "federation_entity" to JsonObject(mapOf(
                "name" to JsonPrimitive("My Entity")
            ))
        ))
        val policy = JsonObject(mapOf(
            "openid_credential_issuer" to JsonObject(mapOf(
                "credential_endpoint" to JsonObject(mapOf(
                    "value" to JsonPrimitive("https://new.example.com/credential")
                ))
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy, "openid_credential_issuer")
        assertEquals("https://new.example.com/credential", result.metadata["credential_endpoint"]!!.jsonPrimitive.content)
    }

    @Test
    fun testApplyPolicyWithEntityTypeNotInPolicy() {
        val metadata = JsonObject(mapOf(
            "openid_credential_verifier" to JsonObject(mapOf(
                "name" to JsonPrimitive("My Verifier")
            ))
        ))
        val policy = JsonObject(mapOf(
            "openid_credential_issuer" to JsonObject(mapOf(
                "credential_endpoint" to JsonObject(mapOf(
                    "essential" to JsonPrimitive(true)
                ))
            ))
        ))

        // Policy doesn't apply to openid_credential_verifier
        val result = MetadataPolicyOperators.applyPolicy(metadata, policy, "openid_credential_verifier")
        assertEquals("My Verifier", result.metadata["name"]!!.jsonPrimitive.content)
    }

    // =========================================================================
    // applyPolicy - combined operators tests
    // =========================================================================

    @Test
    fun testApplyPolicyValueOverridesDefault() {
        val metadata = JsonObject(emptyMap())
        val policy = JsonObject(mapOf(
            "scope" to JsonObject(mapOf(
                "default" to JsonPrimitive("openid"),
                "value" to JsonPrimitive("openid profile")
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        // value operator should win
        assertEquals("openid profile", result.metadata["scope"]!!.jsonPrimitive.content)
    }

    @Test
    fun testApplyPolicyMultipleClaims() {
        val metadata = JsonObject(mapOf(
            "scopes_supported" to JsonArray(listOf(
                JsonPrimitive("openid"),
                JsonPrimitive("profile"),
                JsonPrimitive("phone")
            )),
            "grant_types" to JsonPrimitive("authorization_code")
        ))
        val policy = JsonObject(mapOf(
            "scopes_supported" to JsonObject(mapOf(
                "subset_of" to JsonArray(listOf(
                    JsonPrimitive("openid"),
                    JsonPrimitive("profile")
                ))
            )),
            "token_endpoint" to JsonObject(mapOf(
                "essential" to JsonPrimitive(true)
            )),
            "grant_types" to JsonObject(mapOf(
                "one_of" to JsonArray(listOf(
                    JsonPrimitive("authorization_code"),
                    JsonPrimitive("implicit")
                ))
            ))
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        // subset_of should filter
        val scopes = result.metadata["scopes_supported"]!!.jsonArray
        assertEquals(2, scopes.size)
        // essential missing should warn
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings[0].contains("token_endpoint"))
        // one_of should not warn (value is valid)
        // grant_types should remain
        assertEquals("authorization_code", result.metadata["grant_types"]!!.jsonPrimitive.content)
    }

    @Test
    fun testApplyPolicyNoPolicyEntries() {
        val metadata = JsonObject(mapOf(
            "name" to JsonPrimitive("My Entity")
        ))
        val policy = JsonObject(emptyMap())

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        assertEquals(metadata, result.metadata)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun testApplyPolicyIgnoresNonObjectPolicyEntries() {
        val metadata = JsonObject(mapOf(
            "name" to JsonPrimitive("My Entity")
        ))
        val policy = JsonObject(mapOf(
            "name" to JsonPrimitive("ignored")
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        assertEquals("My Entity", result.metadata["name"]!!.jsonPrimitive.content)
    }
}
