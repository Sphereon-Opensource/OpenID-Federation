package com.sphereon.utils.mime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals


@Serializable
data class PreAuthorizedCode(val pre_authorized_code: String)

@Serializable
data class Grants(val urn_ietf_params_oauth_grant_type_pre_authorized_code: PreAuthorizedCode)

@Serializable
data class TestData(
    val grants: Grants,
    val credential_configuration_ids: List<String>,
    val credential_issuer: String
)

class JsonUrlEncoderTest {
    private val originalJson =
        """{"grants":{"urn:ietf:params:oauth:grant-type:pre-authorized_code":{"pre-authorized_code":"a"}},"credential_configuration_ids":["Woonplaatsverklaring"],"credential_issuer":"https://agent.issuer.bd.demo.sphereon.com"}"""
    private val encodedJson =
        "%7B%22grants%22%3A%7B%22urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Apre-authorized_code%22%3A%7B%22pre-authorized_code%22%3A%22a%22%7D%7D%2C%22credential_configuration_ids%22%3A%5B%22Woonplaatsverklaring%22%5D%2C%22credential_issuer%22%3A%22https%3A%2F%2Fagent.issuer.bd.demo.sphereon.com%22%7D"
    private val encodedJsonFromObject =  // In objects, we can have colons in the field name
        "%7B%22grants%22%3A%7B%22urn_ietf_params_oauth_grant_type_pre_authorized_code%22%3A%7B%22pre_authorized_code%22%3A%22a%22%7D%7D%2C%22credential_configuration_ids%22%3A%5B%22Woonplaatsverklaring%22%5D%2C%22credential_issuer%22%3A%22https%3A%2F%2Fagent.issuer.bd.demo.sphereon.com%22%7D"

    private val testData = TestData(
        grants = Grants(PreAuthorizedCode("a")),
        credential_configuration_ids = listOf("Woonplaatsverklaring"),
        credential_issuer = "https://agent.issuer.bd.demo.sphereon.com"
    )

    @Test
    fun testStringToUrlEncodedValue() {
        val result = originalJson.toUrlEncodedValue()
        assertEquals(encodedJson, result)
    }

    @Test
    fun testJsonElementToUrlEncodedValue() {
        val original: JsonElement = Json.parseToJsonElement(originalJson)
        val result = original.toUrlEncodedValue()
        assertEquals(encodedJson, result)
    }

    @Test
    fun testObjectToUrlEncodedJsonValue() {
        val result = testData.toUrlEncodedJsonValue()
        assertEquals(encodedJsonFromObject, result)
    }

    @Test
    fun testKSerializerToUrlEncodedJsonValue() {
        val serializer = TestData.serializer()
        val result = testData.toUrlEncodedJsonValue(serializer)
        assertEquals(encodedJsonFromObject, result)
    }

    @Test
    fun testFromUrlEncodedValue() {
        val result = encodedJson.fromUrlEncodedValue()
        assertEquals(originalJson, result)
    }

    @Test
    fun testFromUrlEncodedValueToJsonElement() {
        val result = encodedJson.fromUrlEncodedJsonValueToJsonElement()
        assertEquals(Json.parseToJsonElement(originalJson), result)
    }

    @Test
    fun testFromUrlEncodedJsonValue() {
        val result: TestData = encodedJsonFromObject.fromUrlEncodedJsonValue()
        assertEquals(testData, result)
    }

    @Test
    fun testFromUrlEncodedJsonValueWithSerializer() {
        val serializer = TestData.serializer()
        val result: TestData = encodedJsonFromObject.fromUrlEncodedJsonValue(serializer)
        assertEquals(testData, result)
    }
}
