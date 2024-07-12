package com.sphereon.oid.fed.jwks

import com.sphereon.oid.fed.kms.MemoryKeyStore
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.assertNotNull
import kotlin.test.assertEquals


class JWKSGenerationTest {

    private lateinit var jwksGenerator: JWKSGenerator

    @BeforeTest
    fun setUp() {
        jwksGenerator = JWKSGenerator(MemoryKeyStore())
    }

    @Test
    fun `it should generate the JWKS` () {
        assertNotNull(jwksGenerator.generateJWKS())
    }

    @Test
    fun `It should generate JWKS with all keys` () {
        jwksGenerator.generateJWKS()
        jwksGenerator.generateJWKS()
        assertTrue(jwksGenerator.getJWKSet().size() == 2)
    }

    @Test
    fun `It should generate JWKS with selected keys` () {
        val keyOne = jwksGenerator.generateJWKS()
        val keyTwo = jwksGenerator.generateJWKS()
        jwksGenerator.generateJWKS()
        jwksGenerator.generateJWKS()
        assertTrue(jwksGenerator.getJWKSet(keyOne.keyID, keyTwo.keyID).size() == 2)
    }

    @Test
    fun `It should sign a JWT` () {
        val key = jwksGenerator.generateJWKS()
        val payload = "{\"iss\":\"test\",\"sub\":\"test\"}"
        assertTrue(jwksGenerator.sign(key.keyID, payload).startsWith("ey"))
    }
}