package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** OpenID Federation for OpenID Connect 1.1 uses openid_relying_party for client metadata. */
class MetadataPolicyRelyingPartyScopeTest {
    @Test
    fun subsetOfFiltersRelyingPartyScopeTokensAndEmitsString() {
        val result = apply(
            metadata = """{"openid_relying_party":{"scope":"openid profile email"}}""",
            policy = """{"openid_relying_party":{"scope":{"subset_of":["openid","email"]}}}""",
        )

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(JsonPrimitive("openid email"), result.metadata["openid_relying_party"]?.jsonObject?.get("scope"))
    }

    @Test
    fun addAppendsRelyingPartyScopeTokenWithoutChangingOutputType() {
        val result = apply(
            metadata = """{"openid_relying_party":{"scope":"openid profile"}}""",
            policy = """{"openid_relying_party":{"scope":{"add":["email"]}}}""",
        )

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(JsonPrimitive("openid profile email"), result.metadata["openid_relying_party"]?.jsonObject?.get("scope"))
    }

    @Test
    fun defaultAndValueArraysProduceRelyingPartyScopeStrings() {
        val defaulted = apply(
            metadata = """{"openid_relying_party":{}}""",
            policy = """{"openid_relying_party":{"scope":{"default":["openid","profile"]}}}""",
        )
        assertTrue(defaulted.isValid, defaulted.errors.toString())
        assertEquals(JsonPrimitive("openid profile"), defaulted.metadata["openid_relying_party"]?.jsonObject?.get("scope"))

        val replaced = apply(
            metadata = """{"openid_relying_party":{"scope":"old"}}""",
            policy = """{"openid_relying_party":{"scope":{"value":["email","openid"]}}}""",
        )
        assertTrue(replaced.isValid, replaced.errors.toString())
        assertEquals(JsonPrimitive("email openid"), replaced.metadata["openid_relying_party"]?.jsonObject?.get("scope"))
    }

    @Test
    fun nullValueRemovesRelyingPartyScopeAndVoluntaryAbsentScopeStaysAbsent() {
        val removed = apply(
            metadata = """{"openid_relying_party":{"scope":"openid"}}""",
            policy = """{"openid_relying_party":{"scope":{"value":null}}}""",
        )
        assertTrue(removed.isValid, removed.errors.toString())
        assertFalse(removed.metadata["openid_relying_party"]!!.jsonObject.containsKey("scope"))

        val absent = apply(
            metadata = """{"openid_relying_party":{}}""",
            policy = """{"openid_relying_party":{"scope":{"subset_of":["openid"],"essential":false}}}""",
        )
        assertTrue(absent.isValid, absent.errors.toString())
        assertFalse(absent.metadata["openid_relying_party"]!!.jsonObject.containsKey("scope"))
    }

    @Test
    fun relyingPartyScopePolicyDoesNotCreateUndeclaredRole() {
        val result = apply(
            metadata = """{"federation_entity":{}}""",
            policy = """{"openid_relying_party":{"scope":{"default":["openid"]}}}""",
        )

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(setOf("federation_entity"), result.metadata.keys)
    }

    @Test
    fun relyingPartyScopeMetadataMustBeSpaceSeparatedString() {
        val result = apply(
            metadata = """{"openid_relying_party":{"scope":["openid","profile"]}}""",
            policy = """{"openid_relying_party":{"scope":{"subset_of":["openid"]}}}""",
        )

        assertFalse(result.isValid, "Relying Party scope metadata is a space-separated string, not a JSON array")
    }

    @Test
    fun unrelatedRoleScopeStringIsNotTokenizedForArrayPolicy() {
        val result = apply(
            metadata = """{"custom_role":{"scope":"alpha beta"}}""",
            policy = """{"custom_role":{"scope":{"subset_of":["alpha"]}}}""",
        )

        assertFalse(result.isValid, "scope in an unrelated role is an ordinary string parameter")
    }

    @Test
    fun unrelatedRoleScopeValueRemainsAnOrdinaryString() {
        val result = apply(
            metadata = """{"custom_role":{"scope":"alpha beta"}}""",
            policy = """{"custom_role":{"scope":{"value":"gamma delta"}}}""",
        )

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(JsonPrimitive("gamma delta"), result.metadata["custom_role"]?.jsonObject?.get("scope"))
    }

    private fun apply(metadata: String, policy: String): MetadataPolicyOperators.PolicyApplicationResult =
        MetadataPolicyOperators.applyPolicy(obj(metadata), obj(policy))

    private fun obj(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
