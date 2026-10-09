package com.sphereon.openid.fed.services

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompactEntityStatementJwtTest {

    @Test
    fun readsExpFromUnsignedPayload() {
        val jwt = compactJwt("""{"iss":"https://ta.example.com","sub":"https://as.a.example/oidfed","exp":4102444800}""")
        assertEquals(4102444800L, CompactEntityStatementJwt.expEpochSeconds(jwt))
    }

    @Test
    fun readsNumericExpEncodedAsJsonNumber() {
        val jwt = compactJwt("""{"exp":1700000000.0}""")
        assertEquals(1700000000L, CompactEntityStatementJwt.expEpochSeconds(jwt))
    }

    @Test
    fun missingExpReturnsNullInsteadOfSentinel() {
        val jwt = compactJwt("""{"iss":"https://ta.example.com"}""")
        assertNull(CompactEntityStatementJwt.expEpochSeconds(jwt))
        assertNull(CompactEntityStatementJwt.expEpochSeconds("not-a-jwt"))
        assertNull(CompactEntityStatementJwt.expEpochSeconds(""))
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun compactJwt(payloadJson: String): String {
        val header = b64("""{"alg":"none","typ":"entity-statement+jwt"}""")
        val payload = b64(payloadJson)
        return "$header.$payload."
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun b64(value: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(value.encodeToByteArray())
}
