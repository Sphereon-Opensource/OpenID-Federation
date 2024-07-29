package com.sphereon.oid.fed.persistence

import com.sphereon.oid.fed.persistence.database.PlatformSqlDriver
import com.sphereon.oid.fed.persistence.repositories.AccountRepository

actual object Persistence {
    actual val accountRepository: AccountRepository

    init {
        val driver = PlatformSqlDriver().createPostgresDriver(
            System.getenv(Constants.DATASOURCE_URL),
            System.getenv(Constants.DATASOURCE_USER),
            System.getenv(Constants.DATASOURCE_PASSWORD)
        )
        val database = Database(driver)
        accountRepository = AccountRepository(database)
    }
}
