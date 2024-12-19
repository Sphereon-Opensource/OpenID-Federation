package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.AccountDTO
import com.sphereon.oid.fed.openapi.models.CreateAccountDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.extensions.toAccountDTO

class AccountService() {
    private val accountQueries = Persistence.accountQueries

    fun create(account: CreateAccountDTO): AccountDTO {
        val accountAlreadyExists = accountQueries.findByUsername(account.username).executeAsOneOrNull()

        if (accountAlreadyExists != null) {
            throw EntityAlreadyExistsException(Constants.ACCOUNT_ALREADY_EXISTS)
        }

        return accountQueries.create(
            username = account.username,
            identifier = account.identifier,
        ).executeAsOne().toAccountDTO()
    }

    fun findAll(): List<AccountDTO> {
        return accountQueries.findAll().executeAsList().map { it.toAccountDTO() }
    }

    fun getAccountIdentifier(username: String): String {
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)

        val identifier = account.identifier

        if (identifier != null) {
            return identifier
        }

        val rootIdentifier =
            System.getenv("ROOT_IDENTIFIER") ?: throw NotFoundException(Constants.ROOT_IDENTIFIER_NOT_SET)

        if (username == "root") {
            return rootIdentifier
        }

        return "$rootIdentifier/$username"
    }

    fun getAccountByUsername(username: String): Account {
        return accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)
    }

    fun deleteAccount(username: String): Account {
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)

        return accountQueries.delete(account.id).executeAsOne()
    }

    fun usernameToAccountId(username: String): Int {
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)

        return account.id
    }
}
