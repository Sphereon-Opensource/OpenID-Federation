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
import app.cash.sqldelight.db.SqlPreparedStatement
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
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    private val createdRoles = mutableListOf<String>()

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
        createdRoles.forEach { adminExecute("DROP ROLE IF EXISTS $it") }
    }

    private fun adminExecute(sql: String) {
        DriverManager.getConnection(postgres.jdbcUrl, "owner", "owner").use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

    private fun databaseUrl(): String = postgres.jdbcUrl.replace("/postgres", "/$databaseName")

    private fun openSource(user: String = "owner"): HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = databaseUrl()
        username = user
        password = user
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

    @Test
    fun aPopulatedOlderSchemaIsBroughtToTheLatestVersionWithItsDataKept() {
        val baselineVersion = 17L
        openSource().use { source ->
            val driver = source.asJdbcDriver()
            Database.Schema.migrate(driver, 0L, baselineVersion)
            driver.execute(null, "CREATE TABLE schema_version (version INTEGER NOT NULL)", 0)
            driver.execute(null, "INSERT INTO schema_version (version) VALUES ($baselineVersion)", 0)
            driver.execute(null, "INSERT INTO Account (username, identifier) VALUES ('older_leaf', 'https://example.com/older_leaf')", 0)

            runMigrations(driver)

            assertEquals(Database.Schema.version, scalar(driver, "SELECT MAX(version) FROM schema_version"))
            assertEquals(1L, scalar(driver, "SELECT COUNT(*) FROM Account WHERE username = 'older_leaf'"))
            assertEquals(
                Database.Schema.version - 1,
                scalar(driver, "SELECT COUNT(DISTINCT version) FROM schema_migration_history"),
                "earlier files are backfilled, later files are recorded as they run",
            )
            assertEquals(1L, scalar(driver, "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'trustanchorhint'"))
        }
    }

    private fun greenfieldCatalog(): List<String> {
        val reference = "firstboot_ref_" + java.util.UUID.randomUUID().toString().replace("-", "")
        adminExecute("CREATE DATABASE $reference")
        createdDatabases.add(reference)
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl.replace("/postgres", "/$reference")
            username = "owner"
            password = "owner"
            maximumPoolSize = 1
        }).use { source ->
            val driver = source.asJdbcDriver()
            Database.Schema.create(driver)
            return captureSchemaCatalog(driver, "public")
        }
    }

    private fun tableNames(driver: SqlDriver): List<String> = driver.executeQuery(
        null, "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name", parameters = 0,
        mapper = { cursor: SqlCursor ->
            val names = mutableListOf<String>()
            while (cursor.next().value) names.add(cursor.getString(0)!!)
            QueryResult.Value(names)
        },
    ).value

    private fun assertRefusedAndUnchanged(driver: SqlDriver, expectedInMessage: String) {
        val shapeBefore = captureSchemaCatalog(driver, "public")
        val tablesBefore = tableNames(driver)

        val failure = assertFailsWith<IllegalStateException> { runMigrations(driver, MigrationRetryPolicy(pause = {})) }

        assertTrue(failure.message.orEmpty().contains(expectedInMessage), failure.message)
        assertEquals(shapeBefore, captureSchemaCatalog(driver, "public"), "the schema is exactly as it was")
        assertEquals(tablesBefore, tableNames(driver), "no version table, history table or reference schema table is left")
        assertEquals(0L, scalar(driver, "SELECT COUNT(*) FROM pg_namespace WHERE nspname LIKE 'oidf_adopt_ref_%'"), "the reference schema is gone")
    }

    @Test
    fun anAccountOnlySchemaIsAdoptedAndTheLaterFilesRunInOrder() {
        openSource().use { source ->
            val driver = source.asJdbcDriver()
            Database.Schema.migrate(driver, 0L, 2L)
            driver.execute(null, "INSERT INTO Account (username, identifier) VALUES ('adopted_leaf', 'https://example.com/adopted_leaf')", 0)

            runMigrations(driver)

            assertEquals(greenfieldCatalog(), captureSchemaCatalog(driver, "public"))
            assertEquals(Database.Schema.version, scalar(driver, "SELECT MAX(version) FROM schema_version"))
            assertEquals(1L, scalar(driver, "SELECT COUNT(*) FROM Account WHERE username = 'adopted_leaf'"))
            assertEquals(Database.Schema.version - 1, scalar(driver, "SELECT COUNT(DISTINCT version) FROM schema_migration_history"))
        }
    }

    @Test
    fun aSchemaAtFilesOneToSixteenIsAdoptedAndTheLaterFilesRunInOrder() {
        openSource().use { source ->
            val driver = source.asJdbcDriver()
            Database.Schema.migrate(driver, 0L, 17L)

            runMigrations(driver)

            assertEquals(greenfieldCatalog(), captureSchemaCatalog(driver, "public"))
            assertEquals(Database.Schema.version, scalar(driver, "SELECT MAX(version) FROM schema_version"))
        }
    }

    private fun assertLatestShapeWithChangeIsRefused(vararg statements: String) {
        openSource().use { source ->
            val driver = source.asJdbcDriver()
            Database.Schema.create(driver)
            statements.forEach { statement -> driver.execute(null, statement, 0) }

            assertRefusedAndUnchanged(driver, "does not match any version")
        }
    }

    @Test
    fun aChangedColumnDefaultIsRefused() = assertLatestShapeWithChangeIsRefused(
        "ALTER TABLE Account ALTER COLUMN tenant_source SET DEFAULT 'ACCOUNT'",
    )

    @Test
    fun aChangedCheckExpressionIsRefused() = assertLatestShapeWithChangeIsRefused(
        "ALTER TABLE Jwk DROP CONSTRAINT jwk_revocation_reason_token",
        "ALTER TABLE Jwk ADD CONSTRAINT jwk_revocation_reason_token CHECK (revocation_reason IS NULL OR revocation_reason ~ '^[A-Z][a-z0-9_]{0,63}$')",
    )

    @Test
    fun aChangedTimestampPrecisionIsRefused() = assertLatestShapeWithChangeIsRefused(
        "ALTER TABLE Account ALTER COLUMN updated_at TYPE TIMESTAMP(0)",
    )

    @Test
    fun aChangedColumnOrderIsRefused() = assertLatestShapeWithChangeIsRefused(
        "ALTER TABLE Account DROP COLUMN identifier",
        "ALTER TABLE Account ADD COLUMN identifier TEXT",
    )

    @Test
    fun anAddedTriggerIsRefused() = assertLatestShapeWithChangeIsRefused(
        "CREATE FUNCTION oidf_stray_trigger() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RETURN NEW; END \$\$",
        "CREATE TRIGGER oidf_stray BEFORE INSERT ON Account FOR EACH ROW EXECUTE FUNCTION oidf_stray_trigger()",
    )

    @Test
    fun anIdentityColumnIsRefused() = assertLatestShapeWithChangeIsRefused(
        "ALTER TABLE AccountSigningKey ALTER COLUMN revision ADD GENERATED ALWAYS AS IDENTITY",
    )

    @Test
    fun aGeneratedColumnIsRefused() = assertLatestShapeWithChangeIsRefused(
        "ALTER TABLE Account ADD COLUMN oidf_stray TEXT GENERATED ALWAYS AS (username || 'x') STORED",
    )

    @Test
    fun aChangedCollationIsRefused() = assertLatestShapeWithChangeIsRefused(
        "ALTER TABLE Account ALTER COLUMN username TYPE VARCHAR(255) COLLATE \"C\"",
    )

    @Test
    fun enabledRowLevelSecurityIsRefused() = assertLatestShapeWithChangeIsRefused(
        "ALTER TABLE Account ENABLE ROW LEVEL SECURITY",
    )

    @Test
    fun anAddedPolicyIsRefused() = assertLatestShapeWithChangeIsRefused(
        "CREATE POLICY oidf_stray ON Account USING (true)",
    )

    @Test
    fun aStandaloneEnumTypeIsRefused() = assertLatestShapeWithChangeIsRefused(
        "CREATE TYPE oidf_stray_type AS ENUM ('a', 'b')",
    )

    @Test
    fun aStandaloneSequenceIsRefused() = assertLatestShapeWithChangeIsRefused(
        "CREATE SEQUENCE oidf_stray_sequence",
    )

    @Test
    fun aVersionTwoSchemaWithoutTheRootAccountIsRefused() {
        openSource().use { source ->
            val driver = source.asJdbcDriver()
            Database.Schema.migrate(driver, 0L, 2L)
            driver.execute(null, "DELETE FROM Account WHERE username = 'root'", 0)

            assertRefusedAndUnchanged(driver, "root")
        }
    }

    @Test
    fun aRoleWithoutTheCreatePrivilegeOnTheDatabaseGetsAnErrorThatNamesIt() {
        val role = "adopter_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        adminExecute("CREATE ROLE $role LOGIN PASSWORD '$role'")
        createdRoles.add(role)
        DriverManager.getConnection(databaseUrl(), "owner", "owner").use { connection ->
            connection.createStatement().use { statement -> statement.execute("GRANT ALL ON SCHEMA public TO $role") }
        }
        openSource(role).use { source ->
            val driver = source.asJdbcDriver()
            Database.Schema.migrate(driver, 0L, 17L)

            assertRefusedAndUnchanged(driver, "CREATE privilege on the database")
        }
    }

    @Test
    fun aSchemaMissingAColumnOfTheLatestShapeIsRefusedAndLeftUnchanged() {
        openSource().use { source ->
            val driver = source.asJdbcDriver()
            Database.Schema.create(driver)
            driver.execute(null, "ALTER TABLE Jwk DROP COLUMN revocation_reason", 0)

            assertRefusedAndUnchanged(driver, "revocation_reason")
        }
    }

    @Test
    fun aPartlyAppliedFileTwentyThreeIsRefusedAndLeftUnchanged() {
        openSource().use { source ->
            val driver = source.asJdbcDriver()
            Database.Schema.migrate(driver, 0L, 23L)
            driver.execute(null, "ALTER TABLE Jwk ADD COLUMN expires_at TIMESTAMP", 0)

            assertRefusedAndUnchanged(driver, "revocation_reason")
        }
    }

    @Test
    fun aPartlyAppliedFileTwentyOneIsRefusedInsteadOfCascadingThroughTheLaterFiles() {
        openSource().use { source ->
            val driver = source.asJdbcDriver()
            Database.Schema.migrate(driver, 0L, 21L)
            driver.execute(null, "ALTER TABLE Jwk ADD CONSTRAINT jwk_account_id_id_unique UNIQUE (account_id, id)", 0)

            assertRefusedAndUnchanged(driver, "accountsigningkey")
        }
    }

    @Test
    fun aFailedRunRollsBackEverythingAndRecordsNoVersion() {
        openSource().use { source ->
            val failing = FailingDriver(source.asJdbcDriver(), failOn = "CREATE TABLE TrustMark ", sqlState = "42000", failures = Int.MAX_VALUE)

            assertFailsWith<SQLException> { runMigrations(failing, MigrationRetryPolicy(pause = {})) }

            assertEquals(1, failing.failureCount, "a non-transient error is not retried")
            val tables = scalar(failing.delegate, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'")
            assertEquals(0L, tables, "no table, version record or history row of the failed run remains")
        }
    }

    @Test
    fun aTransientFailureIsRetriedAndThenSucceeds() {
        openSource().use { source ->
            val failing = FailingDriver(source.asJdbcDriver(), failOn = "CREATE TABLE TrustMark ", sqlState = "XX000", failures = 2)
            val pauses = mutableListOf<Long>()

            runMigrations(failing, MigrationRetryPolicy(maxAttempts = 3, backoffMillis = 10L, pause = { pauses.add(it) }))

            assertEquals(2, failing.failureCount)
            assertEquals(listOf(10L, 20L), pauses)
            assertEquals(Database.Schema.version, scalar(failing.delegate, "SELECT MAX(version) FROM schema_version"))
            assertEquals(1L, scalar(failing.delegate, "SELECT COUNT(*) FROM schema_version"))
            assertEquals(Database.Schema.version - 1, scalar(failing.delegate, "SELECT COUNT(*) FROM schema_migration_history"), "history has one row per file, none from the failed attempts")
        }
    }

    @Test
    fun aTransientFailureWrappedInAnotherExceptionIsStillRetried() {
        openSource().use { source ->
            val failing = FailingDriver(source.asJdbcDriver(), failOn = "CREATE TABLE TrustMark ", sqlState = "40001", failures = 1, wrap = true)

            runMigrations(failing, MigrationRetryPolicy(maxAttempts = 2, pause = {}))

            assertEquals(1, failing.failureCount)
            assertEquals(Database.Schema.version, scalar(failing.delegate, "SELECT MAX(version) FROM schema_version"))
        }
    }

    @Test
    fun exhaustedRetriesGiveAClearErrorAndLeaveTheDatabaseUnchanged() {
        openSource().use { source ->
            val failing = FailingDriver(source.asJdbcDriver(), failOn = "CREATE TABLE TrustMark ", sqlState = "40P01", failures = Int.MAX_VALUE)

            val failure = assertFailsWith<IllegalStateException> { runMigrations(failing, MigrationRetryPolicy(maxAttempts = 3, pause = {})) }

            assertTrue(failure.message.orEmpty().contains("failed 3 times"), failure.message)
            assertTrue(failure.message.orEmpty().contains("40P01"), failure.message)
            assertTrue(failure.cause is SQLException)
            assertEquals(3, failing.failureCount)
            assertEquals(0L, scalar(failing.delegate, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'"))
        }
    }
}

/** Throws a database error when a statement containing [failOn] runs, for the first [failures] times. */
private class FailingDriver(
    val delegate: SqlDriver,
    private val failOn: String,
    private val sqlState: String,
    private val failures: Int,
    private val wrap: Boolean = false,
) : SqlDriver by delegate {
    var failureCount: Int = 0
        private set

    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
        if (sql.contains(failOn) && failureCount < failures) {
            failureCount++
            val failure = SQLException("injected failure for the test", sqlState)
            throw if (wrap) IllegalStateException("wrapped by a layer above the driver", failure) else failure
        }
        return delegate.execute(identifier, sql, parameters, binders)
    }
}
