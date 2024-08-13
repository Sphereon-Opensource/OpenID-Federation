package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.jwk.generateKeyPair
import com.sphereon.oid.fed.services.extensions.decrypt
import com.sphereon.oid.fed.services.extensions.encrypt
import org.junit.Test
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import com.sphereon.oid.fed.persistence.models.Jwk as JwkPersistence

class KeyServiceTest {
    @Test
    fun testEncryption() {
        val key = generateKeyPair()
        val encryptedKey = key.encrypt()

        if (System.getenv("APP_KEY") == null) {
            assertEquals(key.d, encryptedKey.d)
        } else {
            assertNotEquals(key.d, encryptedKey.d)
        }

        val persistenceJwk = JwkPersistence(
            id = 1,
            account_id = 1,
            d = encryptedKey.d,
            e = encryptedKey.e,
            n = encryptedKey.n,
            x = encryptedKey.x,
            y = encryptedKey.y,
            alg = encryptedKey.alg,
            crv = encryptedKey.crv,
            p = encryptedKey.p,
            q = encryptedKey.q,
            dp = encryptedKey.dp,
            qi = encryptedKey.qi,
            dq = encryptedKey.dq,
            x5t = encryptedKey.x5t,
            x5t_s256 = encryptedKey.x5tS256,
            x5u = encryptedKey.x5u,
            kid = encryptedKey.kid,
            kty = encryptedKey.kty,
            x5c = encryptedKey.x5c?.toTypedArray(),
            created_at = LocalDateTime.now(),
            revoked_reason = null,
            revoked_at = null,
            uuid = UUID.randomUUID(),
            use = encryptedKey.use
        )

        val decryptedPersistenceJwk = persistenceJwk.decrypt()

        assertEquals(key.d, decryptedPersistenceJwk.d)
    }
}
