package com.sphereon.oid.fed.persistence.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.oid.fed.common.Constants
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.util.concurrent.atomic.AtomicReference

actual class PlatformSqlDriver {
    companion object {
        private val dataSourceRef = AtomicReference<HikariDataSource?>(null)

        private fun createDataSource(url: String, username: String, password: String): HikariDataSource {
            val config = HikariConfig()
            config.jdbcUrl = url
            config.username = username
            config.password = password
            config.maximumPoolSize = 20
            config.minimumIdle = 20
            config.connectionTimeout = 30000
            config.idleTimeout = 600000
            config.maxLifetime = 1800000

            config.addDataSourceProperty("cachePrepStmts", "true")
            config.addDataSourceProperty("prepStmtCacheSize", "250")
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            config.addDataSourceProperty("useServerPrepStmts", "true")
            config.poolName = "HikariPool-OpenID-Federation"

            return HikariDataSource(config)
        }
    }

    actual fun createPostgresDriver(url: String, username: String, password: String): SqlDriver {
        return dataSourceRef.updateAndGet { current ->
            current ?: createDataSource(url, username, password)
        }!!.asJdbcDriver()
    }

    actual fun createSqliteDriver(path: String): SqlDriver {
        throw UnsupportedOperationException(Constants.SQLITE_IS_NOT_SUPPORTED_IN_JVM)
    }
}