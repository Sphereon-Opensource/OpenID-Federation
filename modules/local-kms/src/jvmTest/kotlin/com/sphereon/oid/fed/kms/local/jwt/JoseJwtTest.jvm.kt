package com.sphereon.oid.fed.kms.local.jwt

import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import kotlin.test.Test
import kotlin.test.assertTrue

class JoseJwtTest {

    @Test
    fun signTest() {
        val key = RSAKeyGenerator(2048).keyID("key1").generate()
        val signature = sign(
            JwtPayload.parse(
                mutableMapOf<String, Any>(
                    "iss" to "test"
                )
            ),
            JwtHeader.parse(mutableMapOf<String, Any>(
                "typ" to "JWT",
                "alg" to "RS256",
                "kid" to key.keyID)),
            mutableMapOf("key" to key)
        )
        assertTrue { signature.startsWith("ey") }
    }

    @Test
    fun verifyTest() {
        val kid = "key1"
        val key: RSAKey = RSAKeyGenerator(2048).keyID(kid).generate()
        val signature = sign(
            JwtPayload.parse(
                mutableMapOf<String, Any>("iss" to "test")
            ),
            JwtHeader.parse(mutableMapOf<String, Any>(
                    "typ" to "JWT",
                    "alg" to "RS256",
                    "kid" to key.keyID)),
            mutableMapOf("key" to key)
        )
        assertTrue { verify(signature, key, emptyMap()) }
    }
}
