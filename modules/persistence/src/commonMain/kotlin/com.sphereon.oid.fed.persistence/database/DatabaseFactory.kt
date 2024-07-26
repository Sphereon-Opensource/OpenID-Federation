import app.cash.sqldelight.db.SqlDriver
import com.sphereon.oid.fed.persistence.Database

expect class PlatformSqlDriver {
    fun createPostgresDriver(url: String, username: String, password: String): SqlDriver
}

class DriverFactory(private val platformSqlDriver: PlatformSqlDriver) {
    fun createDriver(config: DatabaseConfig): SqlDriver {
        return when (config) {
            is DatabaseConfig.Postgres -> platformSqlDriver.createPostgresDriver(config.url, config.username, config.password)
            else -> throw IllegalArgumentException("Unsupported database config")
        }
    }
}

expect fun createDatabase(driverFactory: DriverFactory): Database

sealed class DatabaseConfig {
    data class Postgres(val url: String, val username: String, val password: String) : DatabaseConfig()
}