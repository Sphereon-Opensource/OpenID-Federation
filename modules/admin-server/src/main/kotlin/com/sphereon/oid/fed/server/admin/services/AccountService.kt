package com.sphereon.oid.fed.server.admin.services

import com.sphereon.oid.fed.openapi.models.AccountDTO
import com.sphereon.oid.fed.openapi.models.CreateAccountDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.extensions.toDTO

class AccountService {
    private val accountRepository = Persistence.accountRepository

    fun create(account: CreateAccountDTO) {
        return accountRepository.create(account)
    }

    fun findAll(): List<AccountDTO> {
        return accountRepository.findAll().map { it.toDTO() }
    }
}
