package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.common.jwt.generateKeyPair
import com.sphereon.oid.fed.openapi.models.JwkDto
import com.sphereon.oid.fed.persistence.models.Jwk
import com.sphereon.oid.fed.services.extensions.toJwkDTO

class KeyService {
    private val accountRepository = Persistence.accountRepository
    private val keyRepository = Persistence.keyRepository

    fun create(accountUsername: String): Jwk {
        val account = accountRepository.findByUsername(accountUsername) ?: throw IllegalArgumentException("Account not found")
        val accountId = account.id
        val key = generateKeyPair()
        return keyRepository.create(accountId, key)
    }

    fun getKeys(accountUsername: String): List<JwkDto> {
        val account = accountRepository.findByUsername(accountUsername) ?: throw IllegalArgumentException("Account not found")
        val accountId = account.id
        return keyRepository.findByAccountId(accountId).map { it.toJwkDTO() }
    }
}
