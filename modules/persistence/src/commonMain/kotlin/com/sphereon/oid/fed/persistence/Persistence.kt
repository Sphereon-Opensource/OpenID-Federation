package com.sphereon.oid.fed.persistence

import com.sphereon.oid.fed.persistence.repositories.AccountRepository
import com.sphereon.oid.fed.persistence.repositories.KeyRepository
import com.sphereon.oid.fed.persistence.repositories.SubordinateRepository

expect object Persistence {
    val accountRepository: AccountRepository
    val keyRepository: KeyRepository
    val subordinateRepository: SubordinateRepository
}
