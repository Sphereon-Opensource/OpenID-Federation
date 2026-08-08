package com.sphereon.openid.fed.client.helpers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineTrustChainPolicyTest {

    private val now = 1_700_000_000L

    private fun stmt(iat: Long, exp: Long) = JsonObject(
        mapOf(
            "iat" to JsonPrimitive(iat),
            "exp" to JsonPrimitive(exp),
        )
    )

    @Test
    fun disabledAlwaysOk() {
        val result = OfflineTrustChainPolicy.DISABLED.evaluate(
            listOf(stmt(now - 10_000, now + 10_000)),
            now,
        )
        assertTrue(result.ok)
    }

    @Test
    fun rejectsWhenTooOld() {
        val policy = OfflineTrustChainPolicy(maxAgeSeconds = 300) // 5 minutes
        val result = policy.evaluate(
            listOf(
                stmt(iat = now - 1000, exp = now + 3600), // 1000s old
                stmt(iat = now - 900, exp = now + 3600),
            ),
            now,
        )
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("too old"))
        assertTrue(result.ageSeconds!! >= 900)
    }

    @Test
    fun acceptsWithinMaxAge() {
        val policy = OfflineTrustChainPolicy(maxAgeSeconds = 3600)
        val result = policy.evaluate(
            listOf(stmt(iat = now - 100, exp = now + 3600)),
            now,
        )
        assertTrue(result.ok, result.reason)
        assertTrue(result.ageSeconds in 95L..105L)
    }

    @Test
    fun rejectsWhenInsufficientRemaining() {
        val policy = OfflineTrustChainPolicy(minRemainingSeconds = 600)
        val result = policy.evaluate(
            listOf(stmt(iat = now - 10, exp = now + 60)), // only 60s left
            now,
        )
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("expires too soon"))
    }

    @Test
    fun acceptsWithEnoughRemaining() {
        val policy = OfflineTrustChainPolicy(minRemainingSeconds = 60)
        val result = policy.evaluate(
            listOf(stmt(iat = now - 10, exp = now + 3600)),
            now,
        )
        assertTrue(result.ok, result.reason)
        assertTrue(result.remainingSeconds!! >= 3500)
    }

    @Test
    fun shortLivedDefaultIsEnabled() {
        assertTrue(OfflineTrustChainPolicy.SHORT_LIVED_DEFAULT.isEnabled)
        assertNull(OfflineTrustChainPolicy.DISABLED.maxAgeSeconds)
    }

    @Test
    fun usesMaxIatAndMinExpAcrossStatements() {
        val policy = OfflineTrustChainPolicy(maxAgeSeconds = 1000, minRemainingSeconds = 100)
        // max iat = now-50 → age 50 ok; min exp = now+200 → remaining 200 ok
        val result = policy.evaluate(
            listOf(
                stmt(iat = now - 500, exp = now + 200),
                stmt(iat = now - 50, exp = now + 5000),
            ),
            now,
        )
        assertTrue(result.ok, result.reason)
        assertTrue(result.ageSeconds in 45L..55L)
        assertTrue(result.remainingSeconds in 195L..205L)
    }
}
