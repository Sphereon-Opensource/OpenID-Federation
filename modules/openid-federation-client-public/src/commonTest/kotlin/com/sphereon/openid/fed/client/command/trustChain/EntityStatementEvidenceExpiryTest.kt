package com.sphereon.openid.fed.client.command.trustChain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntityStatementEvidenceExpiryTest {
    @Test
    fun exactIntegerTokensPreserveTheirWholeSecondValueAcrossLongRange() {
        val expected = listOf(
            "1700003600" to 1_700_003_600L,
            "0" to 0L,
            "-1" to -1L,
            "9223372036854775807" to Long.MAX_VALUE,
            "-9223372036854775808" to Long.MIN_VALUE,
        )
        for ((literal, seconds) in expected) {
            assertEquals(seconds, expiry(literal), "exact integer token $literal")
        }
    }

    @Test
    fun ordinaryFractionsFloorRatherThanRoundOrTruncateTowardZero() {
        assertEquals(1_700_003_600L, expiry("1700003600.5"))
        assertEquals(-2L, expiry("-1.5"))
    }

    @Test
    fun decimalsImmediatelyBelowIntegersNeverRoundExpiryUp() {
        val positive = assertNotNull(expiry("1700000000.9999999999"))
        val negative = assertNotNull(expiry("-1.0000000000000001"))

        assertTrue(positive <= 1_700_000_000L, "positive decimal must not outlive its mathematical floor")
        assertTrue(negative <= -2L, "negative decimal must not round toward -1")
    }

    @Test
    fun finiteExponentUsesAConservativeLowerBound() {
        val seconds = assertNotNull(expiry("1.7000036005e9"))

        assertTrue(seconds <= 1_700_003_600L, "exponent token must not outlive its mathematical floor")
    }

    @Test
    fun outOfRangeTokensReturnNullInsteadOfSaturating() {
        for (literal in listOf(
            "9223372036854775808",
            "-9223372036854775809",
            "1e30",
            "-1e30",
            "1e309",
        )) {
            assertNull(expiry(literal), "out-of-range token $literal")
        }
    }

    @Test
    fun missingAndNonNumericValuesReturnNullWithoutThrowing() {
        assertNull(EntityStatementValidation.conservativeExpiryEpochSeconds(null))
        for (literal in listOf("null", "\"1700003600\"", "{}", "[]", "true", "false")) {
            val value = Json.parseToJsonElement(literal)
            val outcome = runCatching { EntityStatementValidation.conservativeExpiryEpochSeconds(value) }
            assertTrue(outcome.isSuccess, "$literal must not throw: ${outcome.exceptionOrNull()}")
            assertNull(outcome.getOrThrow(), "non-numeric value $literal")
        }
    }

    @Test
    fun conversionLeavesTheSourceJsonElementUnchanged() {
        val value = Json.parseToJsonElement("1700000000.9999999999")
        val original = Json.parseToJsonElement("1700000000.9999999999")

        EntityStatementValidation.conservativeExpiryEpochSeconds(value)

        assertEquals(original, value)
        assertEquals("1700000000.9999999999", value.toString())
    }

    private fun expiry(literal: String): Long? =
        EntityStatementValidation.conservativeExpiryEpochSeconds(Json.parseToJsonElement(literal))
}
