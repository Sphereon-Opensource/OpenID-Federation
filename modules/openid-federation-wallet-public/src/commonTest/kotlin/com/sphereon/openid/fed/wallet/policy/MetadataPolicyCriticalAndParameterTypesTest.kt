package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetadataPolicyCriticalAndParameterTypesTest {
    private val leaf = obj(
        """{"iss":"https://leaf.example","sub":"https://leaf.example","metadata":{"oauth_client":{"test_parameter":"alpha"}}}"""
    )

    @Test
    fun criticalOperatorListMustBeNonemptyArrayOfActualNonemptyStrings() {
        val invalid = listOf("[]", "null", "\"extension\"", "[null]", "[7]", "[true]", "[{}]", "[\"\"]")
        for (literal in invalid) {
            val critical = Json.parseToJsonElement(literal)
            val outcome = runCatching { resolve(listOf(leaf, statement(critical = critical))) }
            assertTrue(outcome.isSuccess, "$literal must produce a controlled policy error: ${outcome.exceptionOrNull()}")
            assertFalse(outcome.getOrThrow().isValid, "$literal is not a valid metadata_policy_crit")
        }
    }

    @Test
    fun criticalOperatorListCannotContainStandardOperatorNames() {
        for (standard in listOf("value", "default", "essential")) {
            val result = resolve(
                listOf(leaf, statement(critical = JsonArray(listOf(JsonPrimitive(standard)))))
            )
            assertFalse(result.isValid, "$standard is standard and cannot be listed in metadata_policy_crit")
        }
    }

    @Test
    fun unsupportedCriticalOperatorFailsEvenWhenUnusedOrInInactiveRole() {
        val critical = JsonArray(listOf(JsonPrimitive("extension")))
        val unused = resolve(listOf(leaf, statement(critical = critical)))
        assertFalse(unused.isValid, "a critical extension cannot become optional merely because it is unused")

        val inactivePolicy = obj("""{"openid_provider":{"other_parameter":{"extension":"ignored"}}}""")
        val inactive = resolve(listOf(leaf, statement(policy = inactivePolicy, critical = critical)))
        assertFalse(inactive.isValid, "a critical extension in an inactive role still requires support")
    }

    @Test
    fun unequalUnknownNoncriticalOperatorValuesAcrossSuperiorsAreIgnored() {
        val immediatePolicy = obj("""{"oauth_client":{"test_parameter":{"extension":"immediate"}}}""")
        val anchorPolicy = obj("""{"oauth_client":{"test_parameter":{"extension":"anchor"}}}""")
        val chain = listOf(
            leaf,
            statement(policy = immediatePolicy),
            statement(issuer = "https://anchor.example", subject = "https://intermediate.example", policy = anchorPolicy)
        )

        val result = resolve(chain)

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(2, result.policiesApplied)
        assertEquals(obj("""{"oauth_client":{"test_parameter":"alpha"}}"""), result.metadata)
    }

    @Test
    fun addDoesNotTreatPresentScalarParameterAsAbsentArray() {
        val metadata = obj("""{"oauth_client":{"test_parameter":"alpha"}}""")
        val policy = obj("""{"oauth_client":{"test_parameter":{"add":["beta"]}}}""")

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)

        assertFalse(result.isValid, "add requires an array-valued present metadata parameter")
    }

    @Test
    fun oneOfDoesNotAcceptArrayValuedParameterOrNestedArrayConfiguration() {
        val metadata = obj("""{"oauth_client":{"test_parameter":["alpha"]}}""")
        val policy = obj("""{"oauth_client":{"test_parameter":{"one_of":[["alpha"]]}}}""")

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)

        assertFalse(result.isValid, "one_of supports scalar string parameters, not nested arrays")
    }

    @Test
    fun supportedArrayAndScalarParameterTypesRemainUsable() {
        val metadata = obj("""{"oauth_client":{"test_parameter":["alpha"],"grant_type":"authorization_code"}}""")
        val policy = obj(
            """{"oauth_client":{"test_parameter":{"add":["beta"],"subset_of":["alpha","beta"],"superset_of":["alpha"]},"grant_type":{"one_of":["authorization_code"]}}}"""
        )

        val result = MetadataPolicyOperators.applyPolicy(metadata, policy)

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(
            obj("""{"test_parameter":["alpha","beta"],"grant_type":"authorization_code"}"""),
            result.metadata["oauth_client"]
        )
    }

    @Test
    fun scopedApplicationStillValidatesInvalidPolicyForAnotherRole() {
        val policy = obj(
            """{"oauth_client":{"test_parameter":{"default":"alpha"}},"openid_provider":{"other_parameter":{"add":"not-an-array"}}}"""
        )
        val result = MetadataPolicyOperators.applyPolicy(
            obj("""{"oauth_client":{}}"""), policy, entityType = "oauth_client"
        )

        assertFalse(result.isValid, "scoping application must not bypass signed policy validation")
    }

    private fun statement(
        issuer: String = "https://intermediate.example",
        subject: String = "https://leaf.example",
        policy: JsonObject? = null,
        critical: JsonElement? = null
    ): JsonObject = buildJsonObject {
        put("iss", issuer)
        put("sub", subject)
        policy?.let { put("metadata_policy", it) }
        critical?.let { put("metadata_policy_crit", it) }
    }

    private fun resolve(statements: List<JsonObject>): MetadataPolicyOperators.TrustChainMetadataResult =
        MetadataPolicyOperators.resolveFromTrustChainPayloads(statements)

    private fun obj(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
