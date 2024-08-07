package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.jwk.generateKeyPair
import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Jwk
import com.sphereon.oid.fed.services.extensions.toJwkAdminDTO

class KeyService {
    private val accountRepository = Persistence.accountRepository
    private val keyRepository = Persistence.keyRepository

    fun create(accountUsername: String): Jwk {
        val account =
            accountRepository.findByUsername(accountUsername) ?: throw IllegalArgumentException("Account not found")
        val accountId = account.id
        val key = generateKeyPair()

        val createdKey = keyRepository.create(
            accountId,
            kty = key.kty,
            e = key.e,
            n = key.n,
            x = key.x,
            y = key.y,
            d = key.d,
            dq = key.dq,
            dp = key.dp,
            qi = key.qi,
            p = key.p,
            q = key.q,
            x5c = key.x5c,
            x5t = key.x5t,
            x5u = key.x5u,
            x5ts256 = key.x5tS256,
            alg = key.alg,
            crv = key.crv,
            kid = key.kid,
            use = key.use,
        )

        return createdKey
    }

    fun getKeys(accountUsername: String): List<JwkAdminDTO> {
        val account =
            accountRepository.findByUsername(accountUsername) ?: throw IllegalArgumentException("Account not found")
        val accountId = account.id
        return keyRepository.findByAccountId(accountId).map { it.toJwkAdminDTO() }
    }

    fun revokeKey(accountUsername: String, keyId: Int, reason: String?): JwkAdminDTO {
        val account =
            accountRepository.findByUsername(accountUsername) ?: throw IllegalArgumentException("Account not found")
        val accountId = account.id

        var key = keyRepository.findById(keyId) ?: throw IllegalArgumentException("Key not found")

        if (key.account_id != accountId) {
            throw IllegalArgumentException("Key does not belong to account")
        }

        if (key.revoked_at != null) {
            throw IllegalArgumentException("Key already revoked")
        }

        keyRepository.revokeKey(keyId, reason)

        key = keyRepository.findById(keyId) ?: throw IllegalArgumentException("Key not found")

        return key.toJwkAdminDTO()
    }
}
