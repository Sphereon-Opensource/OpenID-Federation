package com.sphereon.oid.fed.persistence.repositories

import entities.*
import com.sphereon.oid.fed.persistence.Database

private class JvmAccountRepository(database: Database) : AccountRepository {
    private val accountQueries: AccountQueries = database.accountQueries

    override fun findById(id: Int): Account? {
        return accountQueries.findById(id).executeAsOneOrNull()
    }

    override fun findByUsername(username: String): Account? {
        return accountQueries.findByUsername(username).executeAsOneOrNull()
    }

    override fun create(account: Account) {
        accountQueries.create(account.username)
    }

    override fun findAll(): List<Account> {
        return accountQueries.findAll().executeAsList()
    }

    override fun delete(id: Int) {
        accountQueries.delete(id)
    }

    override fun update(id: Int, account: Account) {
        accountQueries.update(account.username, id)
    }
}

actual fun AccountRepository(database: Database): AccountRepository = JvmAccountRepository(database)