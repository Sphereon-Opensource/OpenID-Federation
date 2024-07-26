import app.cash.sqldelight.db.SqlDriver

expect class PlatformSqlDriver {
    fun createPostgresDriver(url: String, username: String, password: String): SqlDriver
    fun createSqliteDriver(path: String): SqlDriver
}

class DatabaseFactory(private val platformSqlDriver: PlatformSqlDriver) {
    fun createDriver(config: DatabaseConfig): SqlDriver {
        return when (config) {
            is DatabaseConfig.Postgres -> platformSqlDriver.createPostgresDriver(config.url, config.username, config.password)
            is DatabaseConfig.Sqlite -> platformSqlDriver.createSqliteDriver(config.path)
            else -> throw IllegalArgumentException("Unsupported database config")
        }
    }
}

sealed class DatabaseConfig {
    data class Postgres(val url: String, val username: String, val password: String) : DatabaseConfig()
    data class Sqlite(val path: String) : DatabaseConfig()
}