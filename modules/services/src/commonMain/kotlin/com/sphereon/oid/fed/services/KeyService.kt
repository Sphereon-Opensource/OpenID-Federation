package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.jwk.generateKeyPair
import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Jwk
import com.sphereon.oid.fed.services.extensions.decrypt
import com.sphereon.oid.fed.services.extensions.encrypt
import com.sphereon.oid.fed.services.extensions.toJwkAdminDTO

class KeyService {
    private val accountQueries = Persistence.accountQueries
    private val keyQueries = Persistence.keyQueries

    fun create(accountUsername: String): Jwk {
        val account =
            accountQueries.findByUsername(accountUsername).executeAsOne()

        val encryptedKeyPair = generateKeyPair().encrypt()

        val key = keyQueries.create(
            account.id,
            y = encryptedKeyPair.y,
            x = encryptedKeyPair.x,
            d = encryptedKeyPair.d,
            crv = encryptedKeyPair.crv,
            kty = encryptedKeyPair.kty,
            use = encryptedKeyPair.use,
            alg = encryptedKeyPair.alg,
            kid = encryptedKeyPair.kid,
            e = encryptedKeyPair.e,
            n = encryptedKeyPair.n,
            p = encryptedKeyPair.p,
            x5c = encryptedKeyPair.x5c,
            dp = encryptedKeyPair.dp,
            x5t_s256 = encryptedKeyPair.x5tS256,
            q = encryptedKeyPair.q,
            qi = encryptedKeyPair.qi,
            dq = encryptedKeyPair.dq,
            x5u = encryptedKeyPair.x5u,
            x5t = encryptedKeyPair.x5t,
        ).executeAsOne()

        return key
    }

    fun getDecryptedKey(keyId: Int): Jwk {
        var key = keyQueries.findById(keyId).executeAsOne()
        return key.decrypt()
    }

    fun getKeys(accountUsername: String): Array<Jwk> {
        val account =
            accountQueries.findByUsername(accountUsername).executeAsOne()
        return keyQueries.findByAccountId(account.id).executeAsList().toTypedArray()
    }

    fun revokeKey(accountUsername: String, keyId: Int, reason: String?): JwkAdminDTO {
        val account =
            accountQueries.findByUsername(accountUsername).executeAsOne()

        var key = keyQueries.findById(keyId).executeAsOne()

        if (key.account_id != account.id) {
            throw IllegalArgumentException(Constants.KEY_NOT_FOUND)
        }

        if (key.revoked_at != null) {
            throw IllegalArgumentException(Constants.KEY_ALREADY_REVOKED)
        }

        keyQueries.revoke(reason, keyId)

        key = keyQueries.findById(keyId).executeAsOne()

        return key.toJwkAdminDTO()
    }
}
