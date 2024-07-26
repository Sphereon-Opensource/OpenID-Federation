import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.sphereon.oid.fed.persistence.Database

actual class PlatformSqlDriver {
    actual fun createPostgresDriver(url: String, username: String, password: String): SqlDriver {
        val config = HikariConfig()
        config.jdbcUrl = url
        config.username = username
        config.password = password

        val dataSource = HikariDataSource(config)
        val driver = dataSource.asJdbcDriver()
        return driver
    }
}