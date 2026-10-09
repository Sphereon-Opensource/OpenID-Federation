package com.sphereon.openid.fed.services.command.entityConfiguration

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceivedTrustMarkPresentationTest {
    private val entity = "https://leaf.example"
    private val now = 1_800_000_000L

    private fun mark(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"typ":"trust-mark+jwt","alg":"ES256","kid":"k1"}""".toByteArray())
        return "$header.${encoder.encodeToString(payload.toByteArray())}.c2ln"
    }

    @Test
    fun aCurrentMarkIssuedToTheEntityIsPresented() {
        assertTrue(isPresentableReceivedTrustMark(mark("""{"iss":"https://tmi.example","sub":"$entity","trust_mark_type":"https://tm.example/a","iat":${now - 10},"exp":${now + 10}}"""), entity, now))
        // Without exp the Trust Mark does not expire (§7.1).
        assertTrue(isPresentableReceivedTrustMark(mark("""{"iss":"https://tmi.example","sub":"$entity","trust_mark_type":"https://tm.example/a","iat":${now - 10}}"""), entity, now))
    }

    @Test
    fun expiredNotYetIssuedForeignOrMalformedMarksAreLeftOut() {
        assertFalse(isPresentableReceivedTrustMark(mark("""{"sub":"$entity","iat":${now - 10},"exp":$now}"""), entity, now), "expired at exp")
        assertFalse(isPresentableReceivedTrustMark(mark("""{"sub":"$entity","iat":${now + 10}}"""), entity, now), "issued in the future")
        assertFalse(isPresentableReceivedTrustMark(mark("""{"sub":"https://other.example","iat":${now - 10}}"""), entity, now), "issued to another entity")
        assertFalse(isPresentableReceivedTrustMark(mark("""{"sub":"$entity"}"""), entity, now), "iat is required")
        assertFalse(isPresentableReceivedTrustMark("not-a-jwt", entity, now), "not a JWT")
    }
}
