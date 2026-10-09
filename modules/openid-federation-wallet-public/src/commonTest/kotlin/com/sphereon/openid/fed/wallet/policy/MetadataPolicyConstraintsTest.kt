package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetadataPolicyConstraintsTest {
    private fun payload(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    private val leaf = payload(
        """{
          "iss":"https://leaf.example","sub":"https://leaf.example",
          "metadata":{
            "federation_entity":{},
            "openid_provider":{"issuer":"https://leaf.example"},
            "openid_credential_issuer":{"credential_endpoint":"https://leaf.example/credential"}
          }
        }"""
    )

    @Test
    fun absentAllowedTypesKeepsRolesButEmptyListKeepsOnlyFederationEntity() {
        val withoutConstraint = payload(
            """{"iss":"https://anchor.example","sub":"https://leaf.example"}"""
        )
        val emptyAllowed = payload(
            """{"iss":"https://anchor.example","sub":"https://leaf.example","constraints":{"allowed_entity_types":[]}}"""
        )

        val unrestricted = MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(leaf, withoutConstraint))
        val restricted = MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(leaf, emptyAllowed))

        assertTrue(unrestricted.isValid, unrestricted.errors.toString())
        assertEquals(setOf("federation_entity", "openid_provider", "openid_credential_issuer"), unrestricted.metadata.keys)
        assertTrue(restricted.isValid, restricted.errors.toString())
        assertEquals(setOf("federation_entity"), restricted.metadata.keys)
    }

    @Test
    fun cumulativeSuperiorConstraintsFilterLeafRoles() {
        val immediate = payload(
            """{
              "iss":"https://intermediate.example","sub":"https://leaf.example",
              "constraints":{"allowed_entity_types":["openid_provider","openid_credential_issuer"]}
            }"""
        )
        val upper = payload(
            """{
              "iss":"https://anchor.example","sub":"https://intermediate.example",
              "constraints":{"allowed_entity_types":["openid_credential_issuer"]}
            }"""
        )

        val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(leaf, immediate, upper))

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(setOf("federation_entity", "openid_credential_issuer"), result.metadata.keys)
    }

    @Test
    fun immediateSuperiorMetadataIsAppliedBeforeConstraintFilteringAndPolicy() {
        val superior = payload(
            """{
              "iss":"https://anchor.example","sub":"https://leaf.example",
              "metadata":{
                "openid_provider":{"issuer":"https://override.example"},
                "openid_credential_issuer":{"credential_endpoint":"https://override.example/credential"}
              },
              "constraints":{"allowed_entity_types":["openid_provider"]},
              "metadata_policy":{"openid_credential_issuer":{"credential_endpoint":{"value":"https://policy.example/credential"}}}
            }"""
        )

        val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(leaf, superior))

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(setOf("federation_entity", "openid_provider"), result.metadata.keys)
        assertEquals("https://override.example", result.metadata["openid_provider"]?.jsonObject?.get("issuer")?.jsonPrimitive?.content)
        assertEquals(1, result.policiesApplied)
    }

    @Test
    fun criticalPolicyOnFilteredRoleStillFailsClosed() {
        val superior = payload(
            """{
              "iss":"https://anchor.example","sub":"https://leaf.example",
              "constraints":{"allowed_entity_types":[]},
              "metadata_policy_crit":["unknown_operator"],
              "metadata_policy":{"openid_provider":{"issuer":{"unknown_operator":"value"}}}
            }"""
        )

        val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(leaf, superior))

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("unknown_operator") })
    }

    @Test
    fun filteredRequestedEntityTypeReturnsEmptyObjectWithoutPolicy() {
        val superior = payload(
            """{"iss":"https://anchor.example","sub":"https://leaf.example","constraints":{"allowed_entity_types":[]}}"""
        )

        val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(
            listOf(leaf, superior), entityType = "openid_provider"
        )

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(JsonObject(emptyMap()), result.metadata)
        assertEquals(0, result.policiesApplied)
    }

    @Test
    fun policyDefaultAndValueCannotResurrectFilteredEntityType() {
        for (operator in listOf("default", "value")) {
            val superior = payload(
                """{
                  "iss":"https://anchor.example","sub":"https://leaf.example",
                  "constraints":{"allowed_entity_types":[]},
                  "metadata_policy":{"openid_provider":{"issuer":{"$operator":"https://policy.example"}}}
                }"""
            )

            val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(leaf, superior))

            assertTrue(result.isValid, result.errors.toString())
            assertEquals(setOf("federation_entity"), result.metadata.keys, "$operator resurrected filtered role")
        }
    }

    @Test
    fun malformedKnownConstraintsFailClosedDuringMetadataResolution() {
        for (constraint in listOf(
            """{"allowed_entity_types":null}""",
            """{"allowed_entity_types":["federation_entity"]}""",
            """{"max_path_length":-1}"""
        )) {
            val superior = payload(
                """{"iss":"https://anchor.example","sub":"https://leaf.example","constraints":$constraint}"""
            )

            val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(leaf, superior))

            assertFalse(result.isValid, "malformed signed constraint must fail: $constraint")
        }
    }
}
