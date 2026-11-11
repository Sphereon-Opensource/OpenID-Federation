package com.sphereon.openid.fed.services.command.storedstatement

import com.sphereon.openid.fed.services.command.isStoredStatementLive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoredStatementLivenessTest {
    private fun jwt(header: String, payload: String) =
        listOf(header, payload).joinToString(".") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray()) } + ".c2ln"

    @Test
    fun shouldTreatAStatementWithAFutureExpiryAsLiveWhenItsHeaderHasNoAlg() {
        assertTrue(isStoredStatementLive(jwt("""{"kid":"k","typ":"entity-statement+jwt"}""", """{"exp":2000}"""), 1000))
    }

    @Test
    fun shouldTreatAStatementWithAFutureExpiryAsLiveWhenItsHeaderIsComplete() {
        assertTrue(isStoredStatementLive(jwt("""{"alg":"ES256","kid":"k"}""", """{"exp":2000.5}"""), 1000))
    }

    @Test
    fun shouldTreatAnExpiredStatementAsNotLive() {
        assertFalse(isStoredStatementLive(jwt("""{"alg":"ES256"}""", """{"exp":999}"""), 1000))
    }

    @Test
    fun shouldTreatAStatementExpiringExactlyNowAsNotLive() {
        assertFalse(isStoredStatementLive(jwt("""{"alg":"ES256"}""", """{"exp":1000}"""), 1000))
        assertTrue(isStoredStatementLive(jwt("""{"alg":"ES256"}""", """{"exp":1001}"""), 1000))
    }

    @Test
    fun shouldTreatAStatementWhoseExpiryIsNotANumberAsNotLive() {
        assertFalse(isStoredStatementLive(jwt("""{"alg":"ES256"}""", """{"exp":"soon"}"""), 1000))
        assertFalse(isStoredStatementLive(jwt("""{"alg":"ES256"}""", """{"exp":null}"""), 1000))
    }

    @Test
    fun shouldTreatAQuotedOrStructuredExpiryAsNotLive() {
        assertFalse(isStoredStatementLive(jwt("""{"alg":"ES256"}""", """{"exp":"2000"}"""), 1000))
        assertFalse(isStoredStatementLive(jwt("""{"alg":"ES256"}""", """{"exp":{"seconds":2000}}"""), 1000))
    }

    @Test
    fun shouldTreatAStatementWithoutExpiryOrMalformedTextAsNotLive() {
        assertFalse(isStoredStatementLive(jwt("""{"alg":"ES256"}""", """{"iss":"x"}"""), 1000))
        assertFalse(isStoredStatementLive("not-a-jwt", 1000))
        assertFalse(isStoredStatementLive("a.%%%.c", 1000))
    }
}
