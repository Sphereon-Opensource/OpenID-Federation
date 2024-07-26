import com.sphereon.oid.fed.persistence.Database
import com.sphereon.oid.fed.persistence.repositories.AccountRepository

expect class Persistence {
    val database: Database
    val accountRepository: AccountRepository
}
