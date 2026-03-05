package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EndpointConstraintValidatorTest {

    // =========================================================================
    // getRegisteredEndpoints tests
    // =========================================================================

    @Test
    fun testGetRegisteredEndpointsFromArray() {
        val metadata = JsonObject(mapOf(
            "request_uris" to JsonArray(listOf(
                JsonPrimitive("https://verifier.example.com/request/1"),
                JsonPrimitive("https://verifier.example.com/request/2")
            ))
        ))

        val result = EndpointConstraintValidator.getRegisteredEndpoints(metadata, "request_uris")
        assertEquals(2, result.size)
        assertEquals("https://verifier.example.com/request/1", result[0])
        assertEquals("https://verifier.example.com/request/2", result[1])
    }

    @Test
    fun testGetRegisteredEndpointsEmpty() {
        val metadata = JsonObject(emptyMap())

        val result = EndpointConstraintValidator.getRegisteredEndpoints(metadata, "request_uris")
        assertTrue(result.isEmpty())
    }

    @Test
    fun testGetRegisteredEndpointsNonArray() {
        val metadata = JsonObject(mapOf(
            "request_uris" to JsonPrimitive("not an array")
        ))

        val result = EndpointConstraintValidator.getRegisteredEndpoints(metadata, "request_uris")
        assertTrue(result.isEmpty())
    }

    @Test
    fun testGetRegisteredEndpointsEmptyArray() {
        val metadata = JsonObject(mapOf(
            "request_uris" to JsonArray(emptyList())
        ))

        val result = EndpointConstraintValidator.getRegisteredEndpoints(metadata, "request_uris")
        assertTrue(result.isEmpty())
    }

    // =========================================================================
    // validate tests - request_uri
    // =========================================================================

    @Test
    fun testValidateRequestUriRegistered() {
        val metadata = JsonObject(mapOf(
            "request_uris" to JsonArray(listOf(
                JsonPrimitive("https://verifier.example.com/request")
            ))
        ))

        val results = EndpointConstraintValidator.validate(
            metadata = metadata,
            requestUri = "https://verifier.example.com/request"
        )

        assertEquals(1, results.size)
        assertTrue(results[0].valid)
        assertEquals("request_uri", results[0].endpointType)
    }

    @Test
    fun testValidateRequestUriNotRegistered() {
        val metadata = JsonObject(mapOf(
            "request_uris" to JsonArray(listOf(
                JsonPrimitive("https://verifier.example.com/request")
            ))
        ))

        val results = EndpointConstraintValidator.validate(
            metadata = metadata,
            requestUri = "https://evil.example.com/request"
        )

        assertEquals(1, results.size)
        assertFalse(results[0].valid)
        assertEquals("request_uri", results[0].endpointType)
        assertEquals("https://evil.example.com/request", results[0].value)
        assertEquals(listOf("https://verifier.example.com/request"), results[0].registeredValues)
    }

    @Test
    fun testValidateRequestUriNoRegisteredUris() {
        val metadata = JsonObject(emptyMap())

        val results = EndpointConstraintValidator.validate(
            metadata = metadata,
            requestUri = "https://verifier.example.com/request"
        )

        assertEquals(1, results.size)
        assertFalse(results[0].valid)
        assertTrue(results[0].registeredValues.isEmpty())
    }

    // =========================================================================
    // validate tests - response_uri
    // =========================================================================

    @Test
    fun testValidateResponseUriRegistered() {
        val metadata = JsonObject(mapOf(
            "response_uris" to JsonArray(listOf(
                JsonPrimitive("https://verifier.example.com/callback")
            ))
        ))

        val results = EndpointConstraintValidator.validate(
            metadata = metadata,
            responseUri = "https://verifier.example.com/callback"
        )

        assertEquals(1, results.size)
        assertTrue(results[0].valid)
        assertEquals("response_uri", results[0].endpointType)
    }

    @Test
    fun testValidateResponseUriNotRegistered() {
        val metadata = JsonObject(mapOf(
            "response_uris" to JsonArray(listOf(
                JsonPrimitive("https://verifier.example.com/callback")
            ))
        ))

        val results = EndpointConstraintValidator.validate(
            metadata = metadata,
            responseUri = "https://evil.example.com/steal"
        )

        assertEquals(1, results.size)
        assertFalse(results[0].valid)
    }

    // =========================================================================
    // validate tests - redirect_uri
    // =========================================================================

    @Test
    fun testValidateRedirectUriRegistered() {
        val metadata = JsonObject(mapOf(
            "redirect_uris" to JsonArray(listOf(
                JsonPrimitive("https://verifier.example.com/redirect"),
                JsonPrimitive("https://verifier.example.com/redirect2")
            ))
        ))

        val results = EndpointConstraintValidator.validate(
            metadata = metadata,
            redirectUri = "https://verifier.example.com/redirect2"
        )

        assertEquals(1, results.size)
        assertTrue(results[0].valid)
    }

    @Test
    fun testValidateRedirectUriNotRegistered() {
        val metadata = JsonObject(mapOf(
            "redirect_uris" to JsonArray(listOf(
                JsonPrimitive("https://verifier.example.com/redirect")
            ))
        ))

        val results = EndpointConstraintValidator.validate(
            metadata = metadata,
            redirectUri = "https://evil.example.com/redirect"
        )

        assertEquals(1, results.size)
        assertFalse(results[0].valid)
    }

    // =========================================================================
    // validate tests - multiple URIs
    // =========================================================================

    @Test
    fun testValidateAllEndpointsValid() {
        val metadata = JsonObject(mapOf(
            "request_uris" to JsonArray(listOf(JsonPrimitive("https://v.example.com/request"))),
            "response_uris" to JsonArray(listOf(JsonPrimitive("https://v.example.com/response"))),
            "redirect_uris" to JsonArray(listOf(JsonPrimitive("https://v.example.com/redirect")))
        ))

        val results = EndpointConstraintValidator.validate(
            metadata = metadata,
            requestUri = "https://v.example.com/request",
            responseUri = "https://v.example.com/response",
            redirectUri = "https://v.example.com/redirect"
        )

        assertEquals(3, results.size)
        assertTrue(results.all { it.valid })
    }

    @Test
    fun testValidatePartialFailure() {
        val metadata = JsonObject(mapOf(
            "request_uris" to JsonArray(listOf(JsonPrimitive("https://v.example.com/request"))),
            "response_uris" to JsonArray(listOf(JsonPrimitive("https://v.example.com/response")))
        ))

        val results = EndpointConstraintValidator.validate(
            metadata = metadata,
            requestUri = "https://v.example.com/request",
            responseUri = "https://evil.example.com/steal"
        )

        assertEquals(2, results.size)
        assertTrue(results[0].valid)      // request_uri passes
        assertFalse(results[1].valid)     // response_uri fails
    }

    @Test
    fun testValidateNoUrisProvided() {
        val metadata = JsonObject(emptyMap())

        val results = EndpointConstraintValidator.validate(metadata = metadata)

        assertTrue(results.isEmpty())
    }

    // =========================================================================
    // validate tests - edge cases
    // =========================================================================

    @Test
    fun testValidateExactMatchRequired() {
        val metadata = JsonObject(mapOf(
            "request_uris" to JsonArray(listOf(
                JsonPrimitive("https://verifier.example.com/request")
            ))
        ))

        // Trailing slash should not match
        val results = EndpointConstraintValidator.validate(
            metadata = metadata,
            requestUri = "https://verifier.example.com/request/"
        )

        assertEquals(1, results.size)
        assertFalse(results[0].valid)
    }

    @Test
    fun testValidateMultipleRegisteredValues() {
        val metadata = JsonObject(mapOf(
            "request_uris" to JsonArray(listOf(
                JsonPrimitive("https://v.example.com/req1"),
                JsonPrimitive("https://v.example.com/req2"),
                JsonPrimitive("https://v.example.com/req3")
            ))
        ))

        val results = EndpointConstraintValidator.validate(
            metadata = metadata,
            requestUri = "https://v.example.com/req2"
        )

        assertEquals(1, results.size)
        assertTrue(results[0].valid)
        assertEquals(3, results[0].registeredValues.size)
    }
}
