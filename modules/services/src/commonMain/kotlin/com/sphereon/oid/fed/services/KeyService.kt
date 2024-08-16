package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.jwk.generateKeyPair
import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Jwk
import com.sphereon.oid.fed.services.extensions.decrypt
import com.sphereon.oid.fed.services.extensions.encrypt
import com.sphereon.oid.fed.services.extensions.toJwkAdminDTO

class KeyService {
    private val accountRepository = Persistence.accountRepository
    private val keyRepository = Persistence.keyRepository

    fun create(accountUsername: String): Jwk {
        val account =
            accountRepository.findByUsername(accountUsername)
                ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        val key = keyRepository.create(
            account.id,
            generateKeyPair().encrypt()
        )

        return key
    }

    fun getDecryptedKey(keyId: Int): Jwk {
        var key = keyRepository.findById(keyId) ?: throw IllegalArgumentException(Constants.KEY_NOT_FOUND)
        return key.decrypt()
    }

    fun getKeys(accountUsername: String): List<JwkAdminDTO> {
        val account =
            accountRepository.findByUsername(accountUsername)
                ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)
        val accountId = account.id
        return keyRepository.findByAccountId(accountId).map { it.toJwkAdminDTO() }
    }

    fun revokeKey(accountUsername: String, keyId: Int, reason: String?): JwkAdminDTO {
        val account =
            accountRepository.findByUsername(accountUsername)
                ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)
        val accountId = account.id

        var key = keyRepository.findById(keyId) ?: throw IllegalArgumentException(Constants.KEY_NOT_FOUND)

        if (key.account_id != accountId) {
            throw IllegalArgumentException(Constants.KEY_NOT_FOUND)
        }

        if (key.revoked_at != null) {
            throw IllegalArgumentException(Constants.KEY_ALREADY_REVOKED)
        }

        keyRepository.revokeKey(keyId, reason)

        key = keyRepository.findById(keyId) ?: throw IllegalArgumentException(Constants.KEY_NOT_FOUND)

        return key.toJwkAdminDTO()
    }
}
