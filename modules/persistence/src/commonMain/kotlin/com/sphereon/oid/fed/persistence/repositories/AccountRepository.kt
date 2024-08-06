package com.sphereon.oid.fed.persistence.repositories

import app.cash.sqldelight.ExecutableQuery
import com.sphereon.oid.fed.openapi.models.CreateAccountDTO
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.AccountQueries

class AccountRepository(accountQueries: AccountQueries) {
    private val accountQueries = accountQueries

    fun findById(id: Int): Account? {
        return accountQueries.findById(id).executeAsOneOrNull()
    }

    fun findByUsername(username: String): Account? {
        return accountQueries.findByUsername(username).executeAsOneOrNull()
    }

    fun create(account: CreateAccountDTO): ExecutableQuery<Account> {
        return accountQueries.create(username = account.username)
    }

    fun findAll(): List<Account> {
        return accountQueries.findAll().executeAsList()
    }

    fun delete(id: Int) {
        return accountQueries.delete(id)
    }

    fun update(id: Int, account: Account) {
        return accountQueries.update(account.username, id)
    }
}
