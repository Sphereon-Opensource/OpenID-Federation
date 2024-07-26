package com.sphereon.oid.fed.persistence.repositories

import entities.*
import com.sphereon.oid.fed.persistence.Database

class AccountRepository(database: Database) {
    private val accountQueries: AccountQueries = database.accountQueries

    fun findById(id: Int): Account? {
        return accountQueries.findById(id).executeAsOneOrNull()
    }

    fun findByUsername(username: String): Account? {
        return accountQueries.findByUsername(username).executeAsOneOrNull()
    }

    fun create(account: Account) {
        accountQueries.create(account.username)
    }

    fun findAll(): List<Account> {
        return accountQueries.findAll().executeAsList()
    }

    fun delete(id: Int) {
        accountQueries.delete(id)
    }

    fun update(id: Int, account: Account) {
        accountQueries.update(account.username, id)
    }
}
