package com.sphereon.openid.fed.persistence

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A service that connects without schema privileges verifies the schema another service migrated; it runs no DDL,
 * so a role that may only read and write data is enough.
 */
class SchemaVerificationTest {
    companion object {
        private lateinit var postgres: PostgreSQLContainer<Nothing>

        @BeforeClass
        @JvmStatic
        fun startPostgres() {
            assertTrue(
                runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
                "Schema verification needs Docker and a live PostgreSQL container",
            )
            postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                withDatabaseName("oidfed_schema_verification")
                withUsername("owner")
                withPassword("owner")
                start()
            }
            DriverManager.getConnection(postgres.jdbcUrl, "owner", "owner").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE ROLE runtime LOGIN PASSWORD 'runtime'")
                    statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC")
                    statement.execute("GRANT USAGE ON SCHEMA public TO runtime")
                    statement.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO runtime")
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun stopPostgres() {
            if (::postgres.isInitialized) postgres.stop()
        }
    }

    private fun driver(user: String) = HikariDataSource(HikariConfig().apply {
        jdbcUrl = postgres.jdbcUrl
        username = user
        password = user
        maximumPoolSize = 1
    }).asJdbcDriver()

    @Test
    fun aRuntimeRoleVerifiesTheMigratedSchemaWithoutSchemaPrivileges() {
        val runtime = driver("runtime")
        assertFailsWith<IllegalStateException>("an unmigrated database is refused") { verifySchema(runtime, Database.Schema.version) }

        val owner = driver("owner")
        Database.Schema.create(owner)
        owner.execute(null, "CREATE TABLE schema_version (version INTEGER NOT NULL)", 0)
        owner.execute(null, "INSERT INTO schema_version (version) VALUES (${Database.Schema.version - 1})", 0)
        assertFailsWith<IllegalStateException>("a schema behind this library is refused") { verifySchema(runtime, Database.Schema.version) }

        owner.execute(null, "INSERT INTO schema_version (version) VALUES (${Database.Schema.version})", 0)
        verifySchema(runtime, Database.Schema.version)

        assertFailsWith<Exception>("the runtime role cannot change the schema") {
            runtime.execute(null, "CREATE TABLE runtime_probe (id INTEGER)", 0)
        }
    }
}
