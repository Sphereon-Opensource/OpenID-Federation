package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Federation 1.1 §6.1.3 requires string arrays; number and object arrays are optional. */
class MetadataPolicyArrayOperatorItemTypesTest {
    private val arrayOperators = listOf("add", "one_of", "subset_of", "superset_of")
    private val unsupportedItems = listOf(
        "boolean" to "true",
        "null" to "null",
        "nested array" to "[\"nested\"]",
    )

    @Test
    fun activeRoleRejectsUnsupportedArrayOperatorItemsWithControlledError() {
        for (operator in arrayOperators) {
            for ((kind, item) in unsupportedItems) {
                val policy = policy(operator, item)
                val outcome = runCatching {
                    MetadataPolicyOperators.applyPolicy(obj("""{"oauth_client":{}}"""), policy)
                }

                assertTrue(outcome.isSuccess, "$operator with $kind must return a controlled error: ${outcome.exceptionOrNull()}")
                assertFalse(outcome.getOrThrow().isValid, "$operator must reject an array containing $kind")
            }
        }
    }

    @Test
    fun inactiveRoleStillRejectsUnsupportedArrayOperatorItems() {
        for (operator in arrayOperators) {
            for ((kind, item) in unsupportedItems) {
                val outcome = runCatching {
                    MetadataPolicyOperators.applyPolicy(
                        obj("""{"federation_entity":{}}"""),
                        policy(operator, item),
                    )
                }

                assertTrue(outcome.isSuccess, "inactive $operator with $kind must return a controlled error")
                assertFalse(outcome.getOrThrow().isValid, "inactive $operator must reject an array containing $kind")
            }
        }
    }

    @Test
    fun firstPolicyMergeRejectsUnsupportedArrayOperatorItems() {
        for (operator in arrayOperators) {
            for ((kind, item) in unsupportedItems) {
                val outcome = runCatching {
                    MetadataPolicyOperators.mergePolicies(JsonObject(emptyMap()), policy(operator, item))
                }

                assertTrue(outcome.isSuccess, "first $operator with $kind must return a controlled error")
                assertTrue(
                    outcome.getOrThrow() is MetadataPolicyOperators.PolicyMergeResult.Error,
                    "first $operator policy must reject an array containing $kind: ${outcome.getOrThrow()}",
                )
            }
        }
    }

    @Test
    fun stringArrayOperatorsRemainUsable() {
        val result = MetadataPolicyOperators.applyPolicy(
            obj("""{"oauth_client":{"test_parameter":["alpha"],"grant_type":"authorization_code"}}"""),
            obj(
                """{"oauth_client":{"test_parameter":{"add":["beta"],"subset_of":["alpha","beta"],"superset_of":["alpha"]},"grant_type":{"one_of":["authorization_code"]}}}"""
            ),
        )

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(
            obj("""{"test_parameter":["alpha","beta"],"grant_type":"authorization_code"}"""),
            result.metadata["oauth_client"],
        )
    }

    private fun policy(operator: String, item: String): JsonObject =
        obj("""{"oauth_client":{"test_parameter":{"$operator":[$item]}}}""")

    private fun obj(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
