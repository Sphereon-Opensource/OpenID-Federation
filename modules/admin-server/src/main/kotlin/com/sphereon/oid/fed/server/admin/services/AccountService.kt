package com.sphereon.oid.fed.server.admin.services

import com.sphereon.oid.fed.openapi.models.AccountDTO
import com.sphereon.oid.fed.openapi.models.CreateAccountDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.extensions.toDTO
import com.sphereon.oid.fed.server.admin.Constants


class AccountService {
    private val accountRepository = Persistence.accountRepository

    fun create(account: CreateAccountDTO): AccountDTO {
        val accountAlreadyExists = accountRepository.findByUsername(account.username) != null

        if (accountAlreadyExists) {
            throw IllegalArgumentException(Constants.ACCOUNT_ALREADY_EXISTS)
        }

        return accountRepository.create(account).executeAsOne().toDTO()
    }

    fun findAll(): List<AccountDTO> {
        return accountRepository.findAll().map { it.toDTO() }
    }
}
