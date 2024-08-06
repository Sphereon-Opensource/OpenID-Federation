package com.sphereon.oid.fed.persistence

import com.sphereon.oid.fed.persistence.repositories.AccountRepository
import com.sphereon.oid.fed.persistence.repositories.KeyRepository

expect object Persistence {
    val accountRepository: AccountRepository
    val keyRepository: KeyRepository
}
