package com.sphereon.utils.mime

import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonUrlEncoderTestKtor {
    private val originalJson =
        """{"grants":{"urn:ietf:params:oauth:grant-type:pre-authorized_code":{"pre-authorized_code":"a"}},"credential_configuration_ids":["Woonplaatsverklaring"],"credential_issuer":"https://agent.issuer.bd.demo.sphereon.com"}"""
    private val encodedJson =
        "%7B%22grants%22%3A%7B%22urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Apre-authorized_code%22%3A%7B%22pre-authorized_code%22%3A%22a%22%7D%7D%2C%22credential_configuration_ids%22%3A%5B%22Woonplaatsverklaring%22%5D%2C%22credential_issuer%22%3A%22https%3A%2F%2Fagent.issuer.bd.demo.sphereon.com%22%7D"


    @Test
    fun testAppendUrlEncodedValueWithString() {
        val parametersBuilder = ParametersBuilder()
        parametersBuilder.appendUrlEncodedValue("test", originalJson)
        val result = parametersBuilder.build()["test"]
        assertEquals(encodedJson, result)
    }

    @Test
    fun testAppendUrlEncodedValueWithJsonElement() {
        val jsonElement: JsonElement = Json.parseToJsonElement(originalJson)
        val parametersBuilder = ParametersBuilder()
        parametersBuilder.appendUrlEncodedValue("test", jsonElement)
        val result = parametersBuilder.build()["test"]
        assertEquals(encodedJson, result)
    }

    @Test
    fun testFromUrlEncodedValues() {
        val parameters = ParametersBuilder().apply {
            append("test", encodedJson)
        }.build()
        val result = parameters.fromUrlEncodedValues()
        assertEquals(mapOf("test" to originalJson), result)
    }

    @Test
    fun testFromUrlEncodedJsonValuesToJsonElements() {
        val parameters = ParametersBuilder().apply {
            append("test", encodedJson)
        }.build()
        val result = parameters.fromUrlEncodedJsonValuesToJsonElements()
        assertEquals(mapOf("test" to Json.parseToJsonElement(originalJson)), result)
    }

    @Test
    fun testGetUrlEncodedValue() {
        val parameters = ParametersBuilder().apply {
            append("test", encodedJson)
        }.build()
        val result = parameters.getUrlEncodedValue("test")
        assertEquals(originalJson, result)
    }

    @Test
    fun testGetUrlEncodedJsonValueToJsonElement() {
        val parameters = ParametersBuilder().apply {
            append("test", encodedJson)
        }.build()
        val result = parameters.getUrlEncodedJsonValueToJsonElement("test")
        assertEquals(Json.parseToJsonElement(originalJson), result)
    }

    @Test
    fun testGetUrlEncodedValueThrowsException() {
        val parameters = ParametersBuilder().build()
        assertFailsWith<NoSuchElementException> {
            parameters.getUrlEncodedValue("test")
        }
    }

    @Test
    fun testGetUrlEncodedJsonValueToJsonElementThrowsException() {
        val parameters = ParametersBuilder().build()
        assertFailsWith<NoSuchElementException> {
            parameters.getUrlEncodedJsonValueToJsonElement("test")
        }
    }

}
