package com.sphereon.oid.fed.persistence
import PlatformSqlDriver
import com.sphereon.oid.fed.persistence.repositories.AccountRepository

actual object Persistence {
    actual val accountRepository: AccountRepository

    init {
        val driver = PlatformSqlDriver().createPostgresDriver(System.getenv("DB_URL"), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"))
        val database = Database(driver)
        accountRepository = AccountRepository(database)
    }
}