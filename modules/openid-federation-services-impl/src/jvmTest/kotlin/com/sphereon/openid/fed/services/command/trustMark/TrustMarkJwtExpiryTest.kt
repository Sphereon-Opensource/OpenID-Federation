package com.sphereon.openid.fed.services.command.trustMark

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrustMarkJwtExpiryTest {
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    private fun jwt(payload: String) = "${base64.encode("{}".encodeToByteArray())}.${base64.encode(payload.encodeToByteArray())}.signature"

    @Test
    fun shouldReadAnExpWrittenInExponentNotation() {
        assertEquals(1793371311L, readJwtExpSeconds(jwt("""{"iss":"https://a.example","exp":1.793371311E9}""")))
    }

    @Test
    fun shouldReadLowerCaseAndSignedExponents() {
        assertEquals(1793371311L, readJwtExpSeconds(jwt("""{"exp":1.793371311e9}""")))
        assertEquals(1793371311L, readJwtExpSeconds(jwt("""{"exp":1.793371311e+9}""")))
    }

    @Test
    fun shouldReadPlainIntegerAndFractionalSeconds() {
        assertEquals(1793371311L, readJwtExpSeconds(jwt("""{"exp":1793371311}""")))
        assertEquals(1793371311L, readJwtExpSeconds(jwt("""{"exp":1793371311.75}""")))
    }

    @Test
    fun shouldIgnoreExpTextInsideAnotherClaim() {
        assertNull(readJwtExpSeconds(jwt("""{"note":"\"exp\":5","iat":1}""")))
    }

    @Test
    fun shouldReturnNullWhenExpIsMissingOrNotANumber() {
        assertNull(readJwtExpSeconds(jwt("""{"iat":1793371311}""")))
        assertNull(readJwtExpSeconds(jwt("""{"exp":"1793371311"}""")))
    }

    @Test
    fun shouldReturnNullForAMalformedToken() {
        assertNull(readJwtExpSeconds("not-a-jwt"))
        assertNull(readJwtExpSeconds("a.!!!.c"))
    }
}
