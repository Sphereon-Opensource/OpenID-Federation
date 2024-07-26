import com.sphereon.oid.fed.persistence.Database
import com.sphereon.oid.fed.persistence.repositories.AccountRepository

class Persistence(databaseFactory: DatabaseFactory, config: DatabaseConfig) {
    val database: Database
    val accountRepository: AccountRepository

    init {
        val driver = databaseFactory.createDriver(config)
        database = Database(driver)
        accountRepository = AccountRepository(database)
    }
}