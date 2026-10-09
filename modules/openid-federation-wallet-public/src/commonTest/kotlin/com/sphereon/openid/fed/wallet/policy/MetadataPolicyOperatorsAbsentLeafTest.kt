package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetadataPolicyOperatorsAbsentLeafTest {
    private val leaf = Json.parseToJsonElement(
        """{"iss":"https://leaf.example","sub":"https://leaf.example"}"""
    ).jsonObject

    @Test
    fun superiorMetadataDoesNotDeclareAnAbsentLeafEntityType() {
        val superior = Json.parseToJsonElement(
            """{
              "iss":"https://superior.example",
              "sub":"https://leaf.example",
              "metadata":{"openid_credential_issuer":{"credential_endpoint":"https://superior.example/credential"}}
            }"""
        ).jsonObject

        val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(leaf, superior))

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(JsonObject(emptyMap()), result.metadata)
    }

    @Test
    fun rejectsUnknownCriticalOperatorEvenWhenLeafMetadataIsAbsent() {
        val superior = Json.parseToJsonElement(
            """{
              "iss":"https://superior.example",
              "sub":"https://leaf.example",
              "metadata":{"openid_credential_issuer":{}},
              "metadata_policy_crit":["mystery"],
              "metadata_policy":{"openid_credential_issuer":{"credential_endpoint":{"mystery":"value"}}}
            }"""
        ).jsonObject

        val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(leaf, superior))

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Unsupported critical metadata policy operator: 'mystery'") })
    }

    @Test
    fun rejectsMissingEssentialParameterOfLeafDeclaredEntityType() {
        val declaredLeaf = Json.parseToJsonElement(
            """{
              "iss":"https://leaf.example",
              "sub":"https://leaf.example",
              "metadata":{"openid_credential_issuer":{}}
            }"""
        ).jsonObject
        val superior = Json.parseToJsonElement(
            """{
              "iss":"https://superior.example",
              "sub":"https://leaf.example",
              "metadata":{"openid_credential_issuer":{}},
              "metadata_policy":{"openid_credential_issuer":{"credential_endpoint":{"essential":true}}}
            }"""
        ).jsonObject

        val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(declaredLeaf, superior))

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("openid_credential_issuer.credential_endpoint") })
    }

    @Test
    fun defaultAndValuePoliciesDoNotCreateUndeclaredEntityTypes() {
        val declaredLeaf = Json.parseToJsonElement(
            """{
              "iss":"https://leaf.example",
              "sub":"https://leaf.example",
              "metadata":{"federation_entity":{}}
            }"""
        ).jsonObject
        for (operator in listOf("default", "value")) {
            val superior = Json.parseToJsonElement(
                """{
                  "iss":"https://superior.example",
                  "sub":"https://leaf.example",
                  "metadata":{"openid_credential_issuer":{"credential_endpoint":"https://superior.example/credential"}},
                  "metadata_policy":{"openid_credential_issuer":{"credential_endpoint":{"$operator":"https://superior.example/policy"}}}
                }"""
            ).jsonObject

            val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(declaredLeaf, superior))

            assertTrue(result.isValid, result.errors.toString())
            assertEquals(1, result.policiesApplied)
            assertEquals(setOf("federation_entity"), result.metadata.keys, "$operator must not create an undeclared entity type")
        }
    }

    @Test
    fun scopedPolicyDoesNotCreateAnUndeclaredEntityType() {
        val declaredLeaf = Json.parseToJsonElement(
            """{
              "iss":"https://leaf.example",
              "sub":"https://leaf.example",
              "metadata":{"federation_entity":{}}
            }"""
        ).jsonObject
        val superior = Json.parseToJsonElement(
            """{
              "iss":"https://superior.example",
              "sub":"https://leaf.example",
              "metadata_policy":{"openid_credential_issuer":{"credential_endpoint":{"default":"https://superior.example/credential"}}}
            }"""
        ).jsonObject

        val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(
            listOf(declaredLeaf, superior),
            entityType = "openid_credential_issuer"
        )

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(JsonObject(emptyMap()), result.metadata)
        assertEquals(1, result.policiesApplied)
    }

    @Test
    fun malformedPolicyShapeOnUndeclaredEntityTypeStillFailsClosed() {
        val declaredLeaf = Json.parseToJsonElement(
            """{
              "iss":"https://leaf.example",
              "sub":"https://leaf.example",
              "metadata":{"federation_entity":{}}
            }"""
        ).jsonObject
        val superior = Json.parseToJsonElement(
            """{
              "iss":"https://superior.example",
              "sub":"https://leaf.example",
              "metadata_policy":{"openid_credential_issuer":{"credential_endpoint":"not-an-operator-object"}}
            }"""
        ).jsonObject

        val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(declaredLeaf, superior))

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("openid_credential_issuer.credential_endpoint") })
    }

    @Test
    fun fullResolutionRejectsInvalidArrayOperatorOnUndeclaredEntityType() {
        val declaredLeaf = Json.parseToJsonElement(
            """{"iss":"https://leaf.example","sub":"https://leaf.example","metadata":{"federation_entity":{}}}"""
        ).jsonObject
        for (operator in listOf("add", "one_of", "subset_of", "superset_of")) {
            val superior = Json.parseToJsonElement(
                """{
                  "iss":"https://superior.example",
                  "sub":"https://leaf.example",
                  "metadata_policy":{"openid_credential_issuer":{"credential_endpoint":{"$operator":null}}}
                }"""
            ).jsonObject

            val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(declaredLeaf, superior))

            assertFalse(result.isValid, "$operator:null must be rejected even for an undeclared role")
            assertTrue(result.errors.any { it.contains("$operator") }, result.errors.toString())
        }
    }

    @Test
    fun scopedResolutionRejectsInvalidArrayOperatorOnUndeclaredEntityType() {
        val declaredLeaf = Json.parseToJsonElement(
            """{"iss":"https://leaf.example","sub":"https://leaf.example","metadata":{"federation_entity":{}}}"""
        ).jsonObject
        for (operator in listOf("add", "one_of", "subset_of", "superset_of")) {
            val superior = Json.parseToJsonElement(
                """{
                  "iss":"https://superior.example",
                  "sub":"https://leaf.example",
                  "metadata_policy":{"openid_credential_issuer":{"credential_endpoint":{"$operator":null}}}
                }"""
            ).jsonObject

            val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(
                listOf(declaredLeaf, superior), entityType = "openid_credential_issuer"
            )

            assertFalse(result.isValid, "$operator:null must be rejected in scoped resolution")
            assertTrue(result.errors.any { it.contains("$operator") }, result.errors.toString())
        }
    }

    @Test
    fun essentialPolicyDoesNotRequireAnUndeclaredEntityType() {
        val declaredLeaf = Json.parseToJsonElement(
            """{"iss":"https://leaf.example","sub":"https://leaf.example","metadata":{"federation_entity":{}}}"""
        ).jsonObject
        val superior = Json.parseToJsonElement(
            """{
              "iss":"https://superior.example",
              "sub":"https://leaf.example",
              "metadata_policy":{"openid_credential_issuer":{"credential_endpoint":{"essential":true}}}
            }"""
        ).jsonObject

        val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(declaredLeaf, superior))

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(setOf("federation_entity"), result.metadata.keys)
    }

    @Test
    fun acceptsEntirelyAbsentMetadataAndPolicyAsEmptyResolution() {
        val superior = Json.parseToJsonElement(
            """{"iss":"https://superior.example","sub":"https://leaf.example"}"""
        ).jsonObject

        val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(leaf, superior))

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(JsonObject(emptyMap()), result.metadata)
        assertEquals(0, result.policiesApplied)
    }
}
