package com.sphereon.oid.fed.persistence.repositories

import entities.*
import com.sphereon.oid.fed.persistence.Database

interface AccountRepository {
    fun findAll(): List<Account>
    fun findById(id: Int): Account?
    fun findByUsername(username: String): Account?
    fun create(account: Account)
    fun delete(id: Int)
    fun update(id: Int, account: Account)
}

expect fun AccountRepository(database: Database): AccountRepository