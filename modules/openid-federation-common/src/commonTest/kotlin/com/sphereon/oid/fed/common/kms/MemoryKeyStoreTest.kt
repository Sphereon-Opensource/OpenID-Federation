package com.sphereon.oid.fed.jwks

import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.sphereon.oid.fed.kms.MemoryKeyStore
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.*

class MemoryKeyStoreTest {

    lateinit var kms: MemoryKeyStore
    lateinit var keyId: String

    @BeforeTest
    fun setUp() {
        kms = MemoryKeyStore()
        keyId =  UUID.randomUUID().toString()
        val jwk = RSAKeyGenerator(2048)
            .keyUse(KeyUse.SIGNATURE)
            .keyID(keyId)
            .generate()
        kms.importKey(jwk)
    }

    @Test
    fun `It should import a key` () {
        val jwk = RSAKeyGenerator(2048)
            .keyUse(KeyUse.SIGNATURE)
            .keyID(UUID.randomUUID().toString())
            .generate()
        assertTrue(kms.importKey(jwk))
    }

    @Test
    fun `It should retrieve a key` () {
        assertNotNull(kms.getKey(keyId))
    }

    @Test
    fun `It should retrieve a list of keys` () {
        assertTrue(kms.listKeys().size == 1)
    }

    @Test
    fun `It should delete a key` () {
        assertTrue(kms.deleteKey(keyId))
    }
}