import com.sphereon.oid.fed.persistence.Database
import com.sphereon.oid.fed.persistence.repositories.AccountRepository

actual class Persistence {
    actual val database: Database
    actual val accountRepository: AccountRepository

    init {
        println("Persistence created")
        val driver = PlatformSqlDriver().createPostgresDriver("jdbc:postgresql://localhost:5432/oid_fed", "oid_fed", "oid_fed")
        database = Database(driver)
        accountRepository = AccountRepository(database)
    }
}
