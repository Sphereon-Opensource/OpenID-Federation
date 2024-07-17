package com.sphereon.oid.fed.common.jwt

import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import kotlin.test.Test
import kotlin.test.assertTrue

class JoseJwtTest {

    @Test
    fun signTest() {
        val signature = sign("{ \"iss\": \"test\" }", mutableMapOf())
        assertTrue { signature.startsWith("ey") }
    }

    @Test
    fun verifyTest() {
        val kid = "key1"
        val key: RSAKey = RSAKeyGenerator(2048).keyID(kid).generate()
        val signature = sign("{ \"iss\": \"test\" }", mutableMapOf(
            "key" to key,
            "jwtHeader" to "{\"typ\":\"JWT\",\"alg\":\"RS256\",\"kid\":\"${key.keyID}\"}"
        ))
        assertTrue { verify(signature, key) }
    }
}