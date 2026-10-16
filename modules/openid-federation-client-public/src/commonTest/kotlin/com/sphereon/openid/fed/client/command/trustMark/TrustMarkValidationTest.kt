package com.sphereon.openid.fed.client.command.trustMark

import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.JwtHeader
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TrustMarkValidationTest {
    @Test
    fun timeClaimsRequireNumbersAndMalformedClaimsReturnControlledFailures() {
        val now = 1_700_000_000L
        fun mark(iat: JsonElement, exp: JsonElement = JsonPrimitive(now + 60)) = Jwt(
            JwtHeader(alg = "ES256", kid = "key", typ = "trust-mark+jwt"),
            JsonObject(mapOf("iat" to iat, "exp" to exp)), "signature")
        assertNull(TrustMarkValidation.timeError(mark(JsonPrimitive(now - 10)), "type", now))
        assertNull(TrustMarkValidation.timeError(mark(JsonPrimitive(now - 0.5)), "type", now))
        for (bad in listOf(JsonPrimitive((now - 10).toString()), JsonNull, JsonArray(emptyList()),
            JsonObject(emptyMap()), JsonPrimitive(true), JsonPrimitive(now + 60))) {
            assertNotNull(TrustMarkValidation.timeError(mark(bad), "type", now))
        }
        assertNotNull(TrustMarkValidation.timeError(mark(JsonPrimitive(now - 10), JsonPrimitive((now + 60).toString())), "type", now))
    }
}
