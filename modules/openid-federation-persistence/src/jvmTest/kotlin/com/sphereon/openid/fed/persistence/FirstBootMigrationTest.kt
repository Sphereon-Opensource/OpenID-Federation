/*
 * Copyright (c) 2026 Sphereon B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sphereon.openid.fed.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.openid.fed.core.tenant.IdentityInstallState
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Several services, or several replicas, boot against one empty database at the same time and each runs the
 * first-boot migration. Every one of them must come up, and the schema must end up exactly as a single boot
 * leaves it.
 */
class FirstBootMigrationTest {
    companion object {
        private lateinit var postgres: PostgreSQLContainer<Nothing>

        @BeforeClass
        @JvmStatic
        fun startPostgres() {
            assertTrue(
                runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
                "First-boot migration needs Docker and a live PostgreSQL container",
            )
            postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                withDatabaseName("postgres")
                withUsername("owner")
                withPassword("owner")
                start()
            }
        }

        @AfterClass
        @JvmStatic
        fun stopPostgres() {
            if (::postgres.isInitialized) postgres.stop()
        }
    }

    private var databaseName = ""
    private val createdDatabases = mutableListOf<String>()

    private fun createEmptyDatabase() {
        databaseName = "firstboot_" + java.util.UUID.randomUUID().toString().replace("-", "")
        adminExecute("CREATE DATABASE $databaseName")
        createdDatabases.add(databaseName)
    }

    @Before
    fun createDatabase() {
        IdentityInstallState.resetForTests()
        createEmptyDatabase()
    }

    @After
    fun dropDatabase() {
        IdentityInstallState.resetForTests()
        createdDatabases.forEach { adminExecute("DROP DATABASE IF EXISTS $it WITH (FORCE)") }
    }

    private fun adminExecute(sql: String) {
        DriverManager.getConnection(postgres.jdbcUrl, "owner", "owner").use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

    private fun databaseUrl(): String = postgres.jdbcUrl.replace("/postgres", "/$databaseName")

    private fun openSource(): HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = databaseUrl()
        username = "owner"
        password = "owner"
        maximumPoolSize = 2
    })

    private fun scalar(driver: SqlDriver, sql: String): Long = driver.executeQuery(null, sql, parameters = 0, mapper = { cursor: SqlCursor ->
        QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else 0L)
    }).value ?: 0L

    @Test
    fun aSingleBootFromEmptyReachesTheSchemaVersion() {
        openSource().use { source ->
            val driver = source.asJdbcDriver()
            runMigrations(driver)
            assertEquals(Database.Schema.version, scalar(driver, "SELECT MAX(version) FROM schema_version"))
        }
    }

    @Test
    fun repeatedConcurrentFirstBootsFromEmptyAllSucceedAndLeaveOneConsistentSchema() {
        repeat(3) {
            createEmptyDatabase()
            concurrentFirstBootsSucceed()
        }
    }

    @Test
    fun aSchemaWithoutVersionRecordsIsAdoptedAndItsVersionRecorded() {
        openSource().use { source ->
            val driver = source.asJdbcDriver()
            Database.Schema.create(driver)

            runMigrations(driver)

            assertEquals(Database.Schema.version, scalar(driver, "SELECT MAX(version) FROM schema_version"))
            assertEquals(1L, scalar(driver, "SELECT COUNT(*) FROM schema_version"))
        }
    }

    private fun concurrentFirstBootsSucceed() {
        val bootCount = 6
        val sources = List(bootCount) { openSource() }
        val pool = Executors.newFixedThreadPool(bootCount)
        try {
            val startGate = CountDownLatch(1)
            val outcomes = sources.map { source ->
                pool.submit<Throwable?> {
                    startGate.await()
                    runCatching { runMigrations(source.asJdbcDriver()) }.exceptionOrNull()
                }
            }
            startGate.countDown()
            val failures = outcomes.mapNotNull { it.get(120, TimeUnit.SECONDS) }
            assertTrue(failures.isEmpty(), "concurrent first boots failed: " + failures.joinToString { "${it.javaClass.name}: ${it.message}" })

            val driver = sources.first().asJdbcDriver()
            assertEquals(Database.Schema.version, scalar(driver, "SELECT MAX(version) FROM schema_version"))
            assertEquals(
                Database.Schema.version - 1,
                scalar(driver, "SELECT COUNT(DISTINCT version) FROM schema_migration_history"),
                "each migration file is recorded once",
            )
            assertEquals(
                0L,
                scalar(driver, "SELECT COUNT(*) FROM (SELECT version FROM schema_migration_history GROUP BY version HAVING COUNT(*) > 1) duplicated"),
            )
        } finally {
            pool.shutdownNow()
            sources.forEach { it.close() }
        }
    }

    @Test
    fun aMigratedDatabaseBootsAgainUnchanged() {
        openSource().use { source ->
            val driver = source.asJdbcDriver()
            runMigrations(driver)
            val versionRows = scalar(driver, "SELECT COUNT(*) FROM schema_version")
            val historyRows = scalar(driver, "SELECT COUNT(*) FROM schema_migration_history")
            val tables = scalar(driver, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'")

            runMigrations(driver)

            assertEquals(versionRows, scalar(driver, "SELECT COUNT(*) FROM schema_version"))
            assertEquals(historyRows, scalar(driver, "SELECT COUNT(*) FROM schema_migration_history"))
            assertEquals(tables, scalar(driver, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'"))
        }
    }
}
