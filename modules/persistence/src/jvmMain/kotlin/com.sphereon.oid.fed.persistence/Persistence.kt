package com.sphereon.oid.fed.persistence

import com.sphereon.oid.fed.persistence.database.PlatformSqlDriver
import com.sphereon.oid.fed.persistence.repositories.AccountRepository

actual object Persistence {
    actual val accountRepository: AccountRepository

    init {
        val driver = PlatformSqlDriver().createPostgresDriver(
            System.getenv(Constants.DB_URL),
            System.getenv(Constants.DB_USER),
            System.getenv(Constants.DB_PASSWORD)
        )
        val database = Database(driver)
        accountRepository = AccountRepository(database)
    }
}
