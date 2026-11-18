package com.sphereon.openid.fed.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * One PostgreSQL container for the whole test JVM. Each test works in its own database, so advisory locks,
 * catalogs and `public` schemas never overlap between tests. A missing Docker daemon fails the suite instead of
 * skipping it, because a skipped migration suite reports the same green as a passing one.
 */
internal object SharedPostgres {
    private val container: PostgreSQLContainer<Nothing> by lazy {
        check(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)) {
            "The migration tests need Docker and a live PostgreSQL container"
        }
        PostgreSQLContainer<Nothing>("postgres:16-alpine").apply { start() }
    }

    fun createDatabase(): TestDatabase {
        val name = "mig_${UUID.randomUUID().toString().replace("-", "")}"
        adminConnection().use { it.createStatement().use { st -> st.execute("CREATE DATABASE $name") } }
        return TestDatabase(name, container.host, container.firstMappedPort, container.username, container.password)
    }

    internal fun adminConnection(): Connection =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
}

/** A throwaway database with its own connection pool. [close] drops it, terminating any session still attached. */
internal class TestDatabase(
    val name: String,
    host: String,
    port: Int,
    private val user: String,
    private val password: String,
) : AutoCloseable {
    val url: String = "jdbc:postgresql://$host:$port/$name"

    var dataSource: HikariDataSource = openPool()
        private set

    var driver: SqlDriver = dataSource.asJdbcDriver()
        private set

    private fun openPool() = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url
        username = user
        password = this@TestDatabase.password
        maximumPoolSize = 8
    })

    /** Replaces the pool, for tests that terminated a session and must not be handed its dead connection. */
    fun reconnect() {
        dataSource.close()
        dataSource = openPool()
        driver = dataSource.asJdbcDriver()
    }

    fun <T> connection(block: (Connection) -> T): T = dataSource.connection.use(block)

    fun execute(sql: String) = connection { c -> c.createStatement().use { it.execute(sql) } }

    fun executeAll(statements: List<String>) = connection { c ->
        c.createStatement().use { st -> statements.forEach { st.execute(it) } }
    }

    fun scalar(sql: String): String? = connection { c ->
        c.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
    }

    fun rows(sql: String): List<List<String?>> = connection { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val width = rs.metaData.columnCount
                buildList { while (rs.next()) add((1..width).map { rs.getString(it) }) }
            }
        }
    }

    fun tableExists(table: String): Boolean = scalar(
        "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND lower(table_name) = lower('$table')",
    ) == "1"

    fun columnExists(table: String, column: String): Boolean = scalar(
        "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' " +
            "AND lower(table_name) = lower('$table') AND lower(column_name) = lower('$column')",
    ) == "1"

    fun catalog(): String = connection { SchemaCatalogFingerprint.capture(it, "public") }

    override fun close() {
        dataSource.close()
        SharedPostgres.adminConnection().use { c ->
            c.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") }
        }
    }
}
