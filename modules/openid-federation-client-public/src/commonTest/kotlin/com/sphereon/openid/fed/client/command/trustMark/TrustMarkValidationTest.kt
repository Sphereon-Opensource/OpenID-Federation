package com.sphereon.openid.fed.client.command.trustMark

import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.JwtHeader
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private val now = 1_700_000_000L

    private fun markWith(iat: JsonElement, exp: JsonElement? = null) = Jwt(
        JwtHeader(alg = "ES256", kid = "key", typ = "trust-mark+jwt"),
        JsonObject(buildMap { put("iat", iat); if (exp != null) put("exp", exp) }), "signature")

    @Test
    fun shouldToleratePolicySkewInsteadOfTheDefaultFiveSeconds() {
        val policy = TrustMarkStatusPolicy(maximumAgeSeconds = 60, allowedClockSkewSeconds = 600)
        assertEquals(TrustMarkTimeFault.IAT_IN_FUTURE, TrustMarkValidation.timeFault(markWith(JsonPrimitive(now + 30)), now, null))
        assertNull(TrustMarkValidation.timeFault(markWith(JsonPrimitive(now + 30)), now, policy))
        assertEquals(TrustMarkTimeFault.IAT_IN_FUTURE, TrustMarkValidation.timeFault(markWith(JsonPrimitive(now + 601)), now, policy))
        assertEquals(TrustMarkTimeFault.EXPIRED, TrustMarkValidation.timeFault(markWith(JsonPrimitive(now - 10), JsonPrimitive(now - 30)), now, null))
        assertNull(TrustMarkValidation.timeFault(markWith(JsonPrimitive(now - 10), JsonPrimitive(now - 30)), now, policy))
    }

    @Test
    fun shouldRequireRepresentableTimesOnlyUnderAPolicy() {
        val policy = TrustMarkStatusPolicy(maximumAgeSeconds = 60, allowedClockSkewSeconds = 5)
        assertNull(TrustMarkValidation.timeFault(markWith(JsonPrimitive(-5)), now, null))
        assertEquals(TrustMarkTimeFault.IAT_INVALID, TrustMarkValidation.timeFault(markWith(JsonPrimitive(-5)), now, policy))
        assertNull(TrustMarkValidation.timeFault(markWith(JsonPrimitive(now - 10), JsonPrimitive(1.0e300)), now, null))
        assertEquals(TrustMarkTimeFault.EXP_INVALID, TrustMarkValidation.timeFault(markWith(JsonPrimitive(now - 10), JsonPrimitive(1.0e300)), now, policy))
    }
}
