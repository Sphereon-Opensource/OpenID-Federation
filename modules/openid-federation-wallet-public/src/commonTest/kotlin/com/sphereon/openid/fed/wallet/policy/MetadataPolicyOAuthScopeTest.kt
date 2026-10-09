package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Federation 1.1 §6.1.3.1.8: OAuth client scope is tokenized for policy and serialized as a string. */
class MetadataPolicyOAuthScopeTest {
    @Test
    fun subsetOfFiltersSpaceSeparatedOAuthClientScopeAndEmitsString() {
        val result = apply(
            metadata = """{"oauth_client":{"scope":"openid profile email"}}""",
            policy = """{"oauth_client":{"scope":{"subset_of":["openid","email"]}}}"""
        )

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(JsonPrimitive("openid email"), result.metadata["oauth_client"]?.jsonObject?.get("scope"))
    }

    @Test
    fun addAppendsNewOAuthClientScopeTokenWithoutConvertingOutputToArray() {
        val result = apply(
            metadata = """{"oauth_client":{"scope":"openid profile"}}""",
            policy = """{"oauth_client":{"scope":{"add":["email"]}}}"""
        )

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(JsonPrimitive("openid profile email"), result.metadata["oauth_client"]?.jsonObject?.get("scope"))
    }

    @Test
    fun defaultAndValueArraysProduceOAuthClientScopeStrings() {
        val defaulted = apply(
            metadata = """{"oauth_client":{}}""",
            policy = """{"oauth_client":{"scope":{"default":["openid","profile"]}}}"""
        )
        assertTrue(defaulted.isValid, defaulted.errors.toString())
        assertEquals(JsonPrimitive("openid profile"), defaulted.metadata["oauth_client"]?.jsonObject?.get("scope"))

        val replaced = apply(
            metadata = """{"oauth_client":{"scope":"old"}}""",
            policy = """{"oauth_client":{"scope":{"value":["email","openid"]}}}"""
        )
        assertTrue(replaced.isValid, replaced.errors.toString())
        assertEquals(JsonPrimitive("email openid"), replaced.metadata["oauth_client"]?.jsonObject?.get("scope"))
    }

    @Test
    fun nullValueRemovesOAuthClientScopeAndVoluntaryAbsentScopeStaysAbsent() {
        val removed = apply(
            metadata = """{"oauth_client":{"scope":"openid"}}""",
            policy = """{"oauth_client":{"scope":{"value":null}}}"""
        )
        assertTrue(removed.isValid, removed.errors.toString())
        assertFalse(removed.metadata["oauth_client"]!!.jsonObject.containsKey("scope"))

        val absent = apply(
            metadata = """{"oauth_client":{}}""",
            policy = """{"oauth_client":{"scope":{"subset_of":["openid"],"essential":false}}}"""
        )
        assertTrue(absent.isValid, absent.errors.toString())
        assertFalse(absent.metadata["oauth_client"]!!.jsonObject.containsKey("scope"))
    }

    @Test
    fun OAuthScopePolicyDoesNotCreateUndeclaredClientRole() {
        val result = apply(
            metadata = """{"federation_entity":{}}""",
            policy = """{"oauth_client":{"scope":{"default":["openid"]}}}"""
        )

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(setOf("federation_entity"), result.metadata.keys)
    }

    @Test
    fun OAuthScopeMetadataMustUseSpaceSeparatedStringNotJsonArray() {
        val result = apply(
            metadata = """{"oauth_client":{"scope":["openid","profile"]}}""",
            policy = """{"oauth_client":{"scope":{"subset_of":["openid"]}}}"""
        )

        assertFalse(result.isValid, "OAuth client scope metadata is a space-separated string, not a JSON array")
    }

    private fun apply(metadata: String, policy: String): MetadataPolicyOperators.PolicyApplicationResult =
        MetadataPolicyOperators.applyPolicy(obj(metadata), obj(policy))

    private fun obj(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
