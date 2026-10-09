package com.sphereon.openid.fed.client.command.trustChain

import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.JwtHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityStatementNumericDateTest {
    private val now = 1_700_000_000L

    @Test
    fun rejectsNumericStringsForIssuedAtAndExpiration() {
        assertRejected(iat = "\"1699999900\"")
        assertRejected(exp = "\"1700003600\"")
    }

    @Test
    fun rejectsObjectsForIssuedAtAndExpirationWithoutThrowing() {
        assertRejected(iat = "{}")
        assertRejected(exp = "{}")
    }

    @Test
    fun rejectsExponentNumbersThatOverflowFiniteNumericDate() {
        assertRejected(iat = "-1e309")
        assertRejected(exp = "1e309")
    }

    @Test
    fun rejectsFractionalIssuedAtBeyondFiveSecondSkew() {
        assertRejected(iat = "1700000005.5")
    }

    @Test
    fun acceptsFractionalExpirationStillWithinFiveSecondSkew() {
        val result = validate(exp = "1699999995.5")

        assertTrue(result.ok, result.reason)
    }

    @Test
    fun acceptsFiniteFractionalNumericDatesWithoutIntegralRestriction() {
        val result = validate(iat = "1700000004.5", exp = "1700003600.5")

        assertTrue(result.ok, result.reason)
    }

    private fun assertRejected(
        iat: String = "1699999900",
        exp: String = "1700003600"
    ) {
        val outcome = runCatching { validate(iat, exp) }
        assertTrue(outcome.isSuccess, "invalid NumericDate must yield a structural error, not throw: ${outcome.exceptionOrNull()}")
        assertFalse(outcome.getOrThrow().ok, "iat=$iat exp=$exp must be rejected")
    }

    private fun validate(
        iat: String = "1699999900",
        exp: String = "1700003600"
    ): EntityStatementValidation.StructuralResult {
        val payload = Json.parseToJsonElement(
            """{"iss":"https://entity.example","sub":"https://entity.example","iat":$iat,"exp":$exp,"jwks":{"keys":[]}}"""
        ).jsonObject
        val statement = Jwt(
            header = JwtHeader(alg = "ES256", kid = "k1", typ = "entity-statement+jwt"),
            payload = payload,
            signature = "sig"
        )
        return EntityStatementValidation.validateStructure(statement, currentTimeSeconds = now, position = 0)
    }
}
