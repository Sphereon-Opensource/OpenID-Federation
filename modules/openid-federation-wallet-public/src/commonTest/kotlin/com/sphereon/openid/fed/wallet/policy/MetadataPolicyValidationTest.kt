package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetadataPolicyValidationTest {
    private val emptyPolicy = JsonObject(emptyMap())
    private val declaredMetadata = obj("""{"openid_provider":{"test_parameter":["alpha"]}}""")
    private val undeclaredMetadata = obj("""{"federation_entity":{}}""")

    @Test
    fun firstPolicyRejectsMalformedClaimShapeEvenForUndeclaredRole() {
        val policy = obj("""{"openid_provider":{"test_parameter":"not-an-operator-object"}}""")

        assertMergeRejected(emptyPolicy, policy)
        val resolved = resolve(policy, undeclaredMetadata)
        assertFalse(resolved.isValid, resolved.errors.toString())
    }

    @Test
    fun arrayOperatorsRejectScalarAndNullInFirstPolicyAndApplication() {
        for (operator in listOf("add", "one_of", "subset_of", "superset_of")) {
            for (badValue in listOf("\"alpha\"", "null")) {
                val policy = claimPolicy(""""$operator":$badValue""")

                assertMergeRejected(emptyPolicy, policy, "$operator=$badValue")
                assertApplicationRejected(declaredMetadata, policy, "$operator=$badValue")
                assertApplicationRejected(undeclaredMetadata, policy, "inactive $operator=$badValue")
            }
        }
    }

    @Test
    fun essentialRequiresBooleanAndReportsErrorsWithoutThrowing() {
        for (badValue in listOf("\"true\"", "{}", "null")) {
            val policy = claimPolicy(""""essential":$badValue""")

            assertMergeRejected(emptyPolicy, policy, "essential=$badValue")
            assertApplicationRejected(declaredMetadata, policy, "essential=$badValue")
            assertApplicationRejected(undeclaredMetadata, policy, "inactive essential=$badValue")
        }
    }

    @Test
    fun valueCombinationsAreValidatedBeforeValueReplacement() {
        val invalid = listOf(
            """"value":"alpha","one_of":["beta"]""",
            """"value":["alpha"],"add":["beta"]""",
            """"value":["alpha","beta"],"subset_of":["alpha"]""",
            """"value":["alpha"],"superset_of":["beta"]""",
            """"value":null,"default":"alpha"""",
            """"value":null,"essential":true"""
        )
        for (operators in invalid) {
            val policy = claimPolicy(operators)
            assertMergeRejected(emptyPolicy, policy, operators)
            assertApplicationRejected(declaredMetadata, policy, operators)
        }
    }

    @Test
    fun forbiddenAndConflictingArrayCombinationsAreRejected() {
        val invalid = listOf(
            """"add":["alpha"],"one_of":["alpha"]""",
            """"add":["beta"],"subset_of":["alpha"]""",
            """"subset_of":["alpha"],"superset_of":["beta"]"""
        )
        for (operators in invalid) {
            val policy = claimPolicy(operators)
            assertMergeRejected(emptyPolicy, policy, operators)
            assertApplicationRejected(declaredMetadata, policy, operators)
        }
    }

    @Test
    fun independentlyValidPoliciesAreRevalidatedAfterMerge() {
        val cases = listOf(
            claimPolicy(""""value":["alpha"]""") to claimPolicy(""""add":["beta"]"""),
            claimPolicy(""""subset_of":["alpha"]""") to claimPolicy(""""superset_of":["beta"]""")
        )
        for ((superior, subordinate) in cases) {
            val first = MetadataPolicyOperators.mergePolicies(emptyPolicy, superior)
            val second = MetadataPolicyOperators.mergePolicies(emptyPolicy, subordinate)
            assertTrue(first is MetadataPolicyOperators.PolicyMergeResult.Ok, first.toString())
            assertTrue(second is MetadataPolicyOperators.PolicyMergeResult.Ok, second.toString())

            assertMergeRejected((first as MetadataPolicyOperators.PolicyMergeResult.Ok).policy, subordinate, "merged combination")
            val resolved = resolve(subordinate, declaredMetadata, superior)
            assertFalse(resolved.isValid, resolved.errors.toString())
        }
    }

    @Test
    fun validValueCombinationsAndUnknownNoncriticalExtensionRemainUsable() {
        val policy = claimPolicy(
            """"value":["alpha","beta"],"add":["alpha"],"subset_of":["alpha","beta"],"superset_of":["alpha"],"essential":true,"extension":"ignored""""
        )
        val merged = MetadataPolicyOperators.mergePolicies(emptyPolicy, policy)
        assertTrue(merged is MetadataPolicyOperators.PolicyMergeResult.Ok, merged.toString())
        val mergedPolicy = (merged as MetadataPolicyOperators.PolicyMergeResult.Ok).policy
        val applied = MetadataPolicyOperators.applyPolicy(declaredMetadata, mergedPolicy)
        assertTrue(applied.isValid, applied.errors.toString())
        assertEquals(obj("""{"test_parameter":["alpha","beta"]}"""), applied.metadata["openid_provider"])
        val resolved = resolve(policy, declaredMetadata)
        assertTrue(resolved.isValid, resolved.errors.toString())
        assertEquals(1, resolved.policiesApplied)
        assertEquals(obj("""{"test_parameter":["alpha","beta"]}"""), resolved.metadata["openid_provider"])

        val oneOfPolicy = claimPolicy(""""value":"alpha","one_of":["alpha"],"default":"alpha","essential":true""")
        val oneOfMerged = MetadataPolicyOperators.mergePolicies(emptyPolicy, oneOfPolicy)
        assertTrue(oneOfMerged is MetadataPolicyOperators.PolicyMergeResult.Ok, oneOfMerged.toString())
        assertTrue(
            MetadataPolicyOperators.applyPolicy(
                declaredMetadata,
                (oneOfMerged as MetadataPolicyOperators.PolicyMergeResult.Ok).policy
            ).isValid
        )
    }

    @Test
    fun validEssentialPolicyForInactiveRoleDoesNotRequireOrCreateThatRole() {
        val policy = claimPolicy(""""essential":true""")
        val merged = MetadataPolicyOperators.mergePolicies(emptyPolicy, policy)
        assertTrue(merged is MetadataPolicyOperators.PolicyMergeResult.Ok, merged.toString())

        val applied = MetadataPolicyOperators.applyPolicy(
            undeclaredMetadata,
            (merged as MetadataPolicyOperators.PolicyMergeResult.Ok).policy
        )
        assertTrue(applied.isValid, applied.errors.toString())
        assertEquals(setOf("federation_entity"), applied.metadata.keys)
    }

    private fun claimPolicy(operators: String): JsonObject =
        obj("""{"openid_provider":{"test_parameter":{$operators}}}""")

    private fun resolve(
        subordinatePolicy: JsonObject,
        leafMetadata: JsonObject,
        superiorPolicy: JsonObject? = null
    ): MetadataPolicyOperators.TrustChainMetadataResult {
        val leaf = obj("""{"iss":"https://leaf.example","sub":"https://leaf.example","metadata":$leafMetadata}""")
        val immediateSuperior = obj(
            """{"iss":"https://intermediate.example","sub":"https://leaf.example","metadata_policy":$subordinatePolicy}"""
        )
        val chain = if (superiorPolicy == null) listOf(leaf, immediateSuperior) else {
            val anchor = obj(
                """{"iss":"https://anchor.example","sub":"https://intermediate.example","metadata_policy":$superiorPolicy}"""
            )
            listOf(leaf, immediateSuperior, anchor)
        }
        return MetadataPolicyOperators.resolveFromTrustChainPayloads(chain)
    }

    private fun assertMergeRejected(superior: JsonObject, subordinate: JsonObject, context: String = "") {
        val outcome = runCatching { MetadataPolicyOperators.mergePolicies(superior, subordinate) }
        assertTrue(outcome.isSuccess, "$context: invalid policy must produce a merge error, not throw: ${outcome.exceptionOrNull()}")
        assertTrue(
            outcome.getOrThrow() is MetadataPolicyOperators.PolicyMergeResult.Error,
            "$context: invalid policy was accepted by merge"
        )
    }

    private fun assertApplicationRejected(metadata: JsonObject, policy: JsonObject, context: String) {
        val outcome = runCatching { MetadataPolicyOperators.applyPolicy(metadata, policy) }
        assertTrue(outcome.isSuccess, "$context: invalid policy must produce an application error, not throw: ${outcome.exceptionOrNull()}")
        assertFalse(outcome.getOrThrow().isValid, "$context: invalid policy was accepted by application")
    }

    private fun obj(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
