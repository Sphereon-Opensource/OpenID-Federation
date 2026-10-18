package com.sphereon.openid.fed.server.federation.api.http

import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.NoSigningKeyConfiguredError
import com.sphereon.openid.fed.core.error.TrustMarkExpiredError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * OpenID Federation 1.1 section 8.9: errors use the standardized format with the defined error codes, and a
 * server_error is answered with a 5xx status. Library error codes without a federation equivalent must not leak as
 * the error code, and a server-side failure must not be answered as a 400.
 */
class FederationErrorResponsesTest {
    private fun error(body: String?): String =
        Json.parseToJsonElement(requireNotNull(body)).jsonObject.getValue("error").jsonPrimitive.content

    @Test
    fun aServerSideFailureWithoutAFederationCodeIsAServerError() {
        val response = FederationErrorResponses.fromServiceError(NoSigningKeyConfiguredError("tenant-1"))
        assertEquals(500, response.statusCode)
        assertEquals("server_error", error(response.body))
    }

    @Test
    fun aClientSideFailureWithoutAFederationCodeIsAnInvalidRequest() {
        val response = FederationErrorResponses.fromServiceError(TrustMarkExpiredError("https://tm.example/a"))
        assertEquals(400, response.statusCode)
        assertEquals("invalid_request", error(response.body))
    }

    @Test
    fun mappedCodesAreUnchanged() {
        val response = FederationErrorResponses.fromServiceError(InvalidRequestError("bad"))
        assertEquals(400, response.statusCode)
        assertEquals("invalid_request", error(response.body))
    }
}
