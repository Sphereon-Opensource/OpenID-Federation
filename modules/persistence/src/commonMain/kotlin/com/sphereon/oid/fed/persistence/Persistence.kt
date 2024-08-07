package com.sphereon.oid.fed.persistence

import com.sphereon.oid.fed.persistence.repositories.AccountRepository

expect object Persistence {
    val accountRepository: AccountRepository
}
