package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetadataPolicyOperatorsTest {

    /** Wrap claim-level metadata/policy under a synthetic entity type for unit tests. */
    private fun applyClaims(metadata: JsonObject, policy: JsonObject): MetadataPolicyOperators.PolicyApplicationResult {
        val type = "test_entity"
        return MetadataPolicyOperators.applyPolicy(
            metadata = JsonObject(mapOf(type to metadata)),
            policy = JsonObject(mapOf(type to policy)),
            entityType = type
        )
    }

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
        assertTrue(result is MetadataPolicyOperators.PolicyMergeResult.Ok)
        assertEquals(overlay, (result as MetadataPolicyOperators.PolicyMergeResult.Ok).policy)
    }

    @Test
    fun testMergePoliciesEmptyOverlay() {
        val base = JsonObject(mapOf(
            "openid_relying_party" to JsonObject(mapOf(
                "scope" to JsonObject(mapOf(
                    "default" to JsonPrimitive("openid")
                ))
            ))
        ))
        val overlay = JsonObject(emptyMap())

        val result = MetadataPolicyOperators.mergePolicies(base, overlay)
        assertTrue(result is MetadataPolicyOperators.PolicyMergeResult.Ok)
        assertEquals(base, (result as MetadataPolicyOperators.PolicyMergeResult.Ok).policy)
    }

    @Test
    fun testMergePoliciesValueMustBeEqual() {
        val base = JsonObject(mapOf(
            "openid_relying_party" to JsonObject(mapOf(
                "scope" to JsonObject(mapOf(
                    "value" to JsonPrimitive("openid")
                ))
            ))
        ))
        val overlay = JsonObject(mapOf(
            "openid_relying_party" to JsonObject(mapOf(
                "scope" to JsonObject(mapOf(
                    "value" to JsonPrimitive("openid profile")
                ))
            ))
        ))

        val result = MetadataPolicyOperators.mergePolicies(base, overlay)
        assertTrue(result is MetadataPolicyOperators.PolicyMergeResult.Error)
    }

    @Test
    fun testMergePoliciesDeepMergeClaimOperators() {
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
        assertTrue(result is MetadataPolicyOperators.PolicyMergeResult.Ok)
        val issuerPolicy = (result as MetadataPolicyOperators.PolicyMergeResult.Ok)
            .policy["openid_credential_issuer"]!!.jsonObject
        assertTrue(issuerPolicy.containsKey("credential_endpoint"))
        assertTrue(issuerPolicy.containsKey("authorization_endpoint"))
    }

    @Test
    fun testMergePoliciesSubsetOfIntersection() {
        val superior = JsonObject(mapOf(
            "openid_provider" to JsonObject(mapOf(
                "algs" to JsonObject(mapOf(
                    "subset_of" to JsonArray(listOf(
                        JsonPrimitive("RS256"),
                        JsonPrimitive("RS384"),
                        JsonPrimitive("RS512")
                    ))
                ))
            ))
        ))
        val subordinate = JsonObject(mapOf(
            "openid_provider" to JsonObject(mapOf(
                "algs" to JsonObject(mapOf(
                    "subset_of" to JsonArray(listOf(
                        JsonPrimitive("RS256"),
                        JsonPrimitive("ES256")
                    ))
                ))
            ))
        ))

        val result = MetadataPolicyOperators.mergePolicies(superior, subordinate)
        assertTrue(result is MetadataPolicyOperators.PolicyMergeResult.Ok)
        val algs = (result as MetadataPolicyOperators.PolicyMergeResult.Ok)
            .policy["openid_provider"]!!.jsonObject["algs"]!!.jsonObject["subset_of"]!!.jsonArray
        assertEquals(1, algs.size)
        assertTrue(algs.contains(JsonPrimitive("RS256")))
    }

    @Test
    fun testMergePoliciesEssentialOr() {
        val superior = JsonObject(mapOf(
            "openid_relying_party" to JsonObject(mapOf(
                "scope" to JsonObject(mapOf(
                    "essential" to JsonPrimitive(false)
                ))
            ))
        ))
        val subordinate = JsonObject(mapOf(
            "openid_relying_party" to JsonObject(mapOf(
                "scope" to JsonObject(mapOf(
                    "essential" to JsonPrimitive(true)
                ))
            ))
        ))

        val result = MetadataPolicyOperators.mergePolicies(superior, subordinate)
        assertTrue(result is MetadataPolicyOperators.PolicyMergeResult.Ok)
        val essential = (result as MetadataPolicyOperators.PolicyMergeResult.Ok)
            .policy["openid_relying_party"]!!.jsonObject["scope"]!!.jsonObject["essential"]!!
            .jsonPrimitive.boolean
        assertTrue(essential)
    }

    @Test
    fun testMergePoliciesOneOfEmptyIntersectionIsError() {
        val superior = JsonObject(mapOf(
            "openid_relying_party" to JsonObject(mapOf(
                "grant_types" to JsonObject(mapOf(
                    "one_of" to JsonArray(listOf(JsonPrimitive("authorization_code")))
                ))
            ))
        ))
        val subordinate = JsonObject(mapOf(
            "openid_relying_party" to JsonObject(mapOf(
                "grant_types" to JsonObject(mapOf(
                    "one_of" to JsonArray(listOf(JsonPrimitive("client_credentials")))
                ))
            ))
        ))

        val result = MetadataPolicyOperators.mergePolicies(superior, subordinate)
        assertTrue(result is MetadataPolicyOperators.PolicyMergeResult.Error)
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

        val result = applyClaims(metadata, policy)
        assertTrue(result.isValid)
        assertEquals("private_key_jwt", result.metadata["token_endpoint_auth_method"]!!.jsonPrimitive.content)
    }

    @Test
    fun testApplyPolicyValueNullRemovesClaim() {
        val metadata = JsonObject(mapOf(
            "obsolete" to JsonPrimitive("x")
        ))
        val policy = JsonObject(mapOf(
            "obsolete" to JsonObject(mapOf(
                "value" to JsonNull
            ))
        ))

        val result = applyClaims(metadata, policy)
        assertTrue(result.isValid)
        assertFalse(result.metadata.containsKey("obsolete"))
    }

    @Test
    fun testApplyPolicyValueOperatorSetsAbsentValue() {
        val metadata = JsonObject(emptyMap())
        val policy = JsonObject(mapOf(
            "token_endpoint_auth_method" to JsonObject(mapOf(
                "value" to JsonPrimitive("private_key_jwt")
            ))
        ))

        val result = applyClaims(metadata, policy)
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

        val result = applyClaims(metadata, policy)
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

        val result = applyClaims(metadata, policy)
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

        val result = applyClaims(metadata, policy)
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

        val result = applyClaims(metadata, policy)
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun testApplyPolicyOneOfInvalidIsError() {
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

        val result = applyClaims(metadata, policy)
        assertFalse(result.isValid)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].contains("grant_types"))
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

        val result = applyClaims(metadata, policy)
        assertTrue(result.isValid)
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

        val result = applyClaims(metadata, policy)
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

        val result = applyClaims(metadata, policy)
        assertTrue(result.isValid)
    }

    @Test
    fun testApplyPolicySupersetOfMissingIsError() {
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

        val result = applyClaims(metadata, policy)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("vp_token") })
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

        val result = applyClaims(metadata, policy)
        val scopes = result.metadata["scopes_supported"]!!.jsonArray
        assertEquals(3, scopes.size)
        assertTrue(scopes.contains(JsonPrimitive("openid")))
        assertTrue(scopes.contains(JsonPrimitive("profile")))
        assertTrue(scopes.contains(JsonPrimitive("email")))
    }

    @Test
    fun testApplyPolicyAddDedupes() {
        val metadata = JsonObject(mapOf(
            "scopes_supported" to JsonArray(listOf(JsonPrimitive("openid")))
        ))
        val policy = JsonObject(mapOf(
            "scopes_supported" to JsonObject(mapOf(
                "add" to JsonArray(listOf(JsonPrimitive("openid"), JsonPrimitive("profile")))
            ))
        ))

        val result = applyClaims(metadata, policy)
        val scopes = result.metadata["scopes_supported"]!!.jsonArray
        assertEquals(2, scopes.size)
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

        val result = applyClaims(metadata, policy)
        val scopes = result.metadata["scopes_supported"]!!.jsonArray
        assertEquals(1, scopes.size)
        assertTrue(scopes.contains(JsonPrimitive("openid")))
    }

    // =========================================================================
    // applyPolicy - essential operator tests
    // =========================================================================

    @Test
    fun testApplyPolicyEssentialMissingIsError() {
        val metadata = JsonObject(emptyMap())
        val policy = JsonObject(mapOf(
            "credential_endpoint" to JsonObject(mapOf(
                "essential" to JsonPrimitive(true)
            ))
        ))

        val result = applyClaims(metadata, policy)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("credential_endpoint") && it.contains("Essential") })
    }

    @Test
    fun testApplyPolicyEssentialPresentNoError() {
        val metadata = JsonObject(mapOf(
            "credential_endpoint" to JsonPrimitive("https://issuer.example.com/credential")
        ))
        val policy = JsonObject(mapOf(
            "credential_endpoint" to JsonObject(mapOf(
                "essential" to JsonPrimitive(true)
            ))
        ))

        val result = applyClaims(metadata, policy)
        assertTrue(result.isValid)
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

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy, "openid_credential_verifier")
        assertEquals("My Verifier", result.metadata["name"]!!.jsonPrimitive.content)
    }

    // =========================================================================
    // applySuperiorMetadata
    // =========================================================================

    @Test
    fun testApplySuperiorMetadataOverridesSameEntityType() {
        val leaf = JsonObject(mapOf(
            "openid_credential_verifier" to JsonObject(mapOf(
                "request_uris" to JsonArray(listOf(JsonPrimitive("https://leaf.example/request"))),
                "name" to JsonPrimitive("Leaf")
            )),
            "federation_entity" to JsonObject(mapOf(
                "organization_name" to JsonPrimitive("Leaf Org")
            ))
        ))
        val superior = JsonObject(mapOf(
            "openid_credential_verifier" to JsonObject(mapOf(
                "request_uris" to JsonArray(listOf(JsonPrimitive("https://approved.example/request")))
            )),
            "openid_credential_issuer" to JsonObject(mapOf(
                "credential_endpoint" to JsonPrimitive("https://ignored.example")
            ))
        ))

        val result = MetadataPolicyOperators.applySuperiorMetadata(leaf, superior)
        val verifier = result["openid_credential_verifier"]!!.jsonObject
        assertEquals(
            "https://approved.example/request",
            verifier["request_uris"]!!.jsonArray[0].jsonPrimitive.content
        )
        assertEquals("Leaf", verifier["name"]!!.jsonPrimitive.content)
        // Superior metadata for types not in leaf is ignored
        assertFalse(result.containsKey("openid_credential_issuer"))
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

        val result = applyClaims(metadata, policy)
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

        val result = applyClaims(metadata, policy)
        val scopes = result.metadata["scopes_supported"]!!.jsonArray
        assertEquals(2, scopes.size)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("token_endpoint") })
        assertEquals("authorization_code", result.metadata["grant_types"]!!.jsonPrimitive.content)
    }

    @Test
    fun testApplyPolicyNoPolicyEntries() {
        val metadata = JsonObject(mapOf(
            "openid_relying_party" to JsonObject(mapOf(
                "name" to JsonPrimitive("My Entity")
            ))
        ))
        val policy = JsonObject(emptyMap())

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        assertEquals(metadata, result.metadata)
        assertTrue(result.isValid)
    }

    @Test
    fun testApplyPolicyNonObjectEntityTypeEntryIsError() {
        val metadata = JsonObject(mapOf(
            "openid_relying_party" to JsonObject(mapOf(
                "name" to JsonPrimitive("My Entity")
            ))
        ))
        val policy = JsonObject(mapOf(
            "openid_relying_party" to JsonPrimitive("ignored")
        ))

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)
        assertFalse(result.isValid)
    }

    @Test
    fun testValidateCriticalOperatorsUnsupported() {
        val policy = JsonObject(mapOf(
            "openid_relying_party" to JsonObject(mapOf(
                "uri" to JsonObject(mapOf(
                    "regexp" to JsonPrimitive("^https://.*")
                ))
            ))
        ))
        val errors = MetadataPolicyOperators.validateCriticalOperators(policy, listOf("regexp"))
        assertTrue(errors.any { it.contains("regexp") })
    }

    @Test
    fun testValidateCriticalOperatorsStandardIgnored() {
        val policy = JsonObject(mapOf(
            "openid_relying_party" to JsonObject(mapOf(
                "scope" to JsonObject(mapOf(
                    "essential" to JsonPrimitive(true)
                ))
            ))
        ))
        val errors = MetadataPolicyOperators.validateCriticalOperators(policy, listOf("essential"))
        assertTrue(errors.isEmpty())
    }
}
