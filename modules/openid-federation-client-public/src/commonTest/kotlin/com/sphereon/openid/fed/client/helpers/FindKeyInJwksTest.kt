package com.sphereon.openid.fed.client.helpers

import com.sphereon.openid.fed.openapi.models.Jwk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** OpenID Federation 1.1 section 16: a kid is compared code point by code point, without trimming. */
class FindKeyInJwksTest {
    private val keys = arrayOf(Jwk(kty = "EC", kid = "key-1"), Jwk(kty = "EC", kid = "key-2 "))

    @Test
    fun anExactKidMatches() {
        assertEquals("key-1", findKeyInJwks(keys, "key-1")?.kid)
        assertEquals("key-2 ", findKeyInJwks(keys, "key-2 ")?.kid)
    }

    @Test
    fun aKidThatDiffersOnlyInWhitespaceDoesNotMatch() {
        assertNull(findKeyInJwks(keys, " key-1"))
        assertNull(findKeyInJwks(keys, "key-2"))
    }
}
