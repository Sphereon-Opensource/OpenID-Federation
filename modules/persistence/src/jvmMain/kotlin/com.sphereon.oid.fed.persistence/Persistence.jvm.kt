package com.sphereon.oid.fed.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.sphereon.oid.fed.persistence.database.PlatformSqlDriver
import com.sphereon.oid.fed.persistence.models.AccountQueries
import com.sphereon.oid.fed.persistence.models.EntityConfigurationStatementQueries
import com.sphereon.oid.fed.persistence.models.KeyQueries
import com.sphereon.oid.fed.persistence.models.SubordinateQueries

actual object Persistence {
    actual val entityConfigurationStatementQueries: EntityConfigurationStatementQueries
    actual val accountQueries: AccountQueries
    actual val keyQueries: KeyQueries
    actual val subordinateQueries: SubordinateQueries

    init {
        val driver = getDriver()
        runMigrations(driver)

        val database = Database(driver)
        accountQueries = database.accountQueries
        entityConfigurationStatementQueries = database.entityConfigurationStatementQueries
        keyQueries = database.keyQueries
        subordinateQueries = database.subordinateQueries
    }

    private fun getDriver(): SqlDriver {
        return PlatformSqlDriver().createPostgresDriver(
            System.getenv(Constants.DATASOURCE_URL),
            System.getenv(Constants.DATASOURCE_USER),
            System.getenv(Constants.DATASOURCE_PASSWORD)
        )
    }

    private fun runMigrations(driver: SqlDriver) {
        setupSchemaVersioningTable(driver)

        val currentVersion = getCurrentDatabaseVersion(driver)
        val newVersion = Database.Schema.version

        if (currentVersion < newVersion) {
            Database.Schema.migrate(driver, currentVersion, newVersion)
            updateDatabaseVersion(driver, newVersion)
        }
    }

    private fun setupSchemaVersioningTable(driver: SqlDriver) {
        driver.execute(null, "CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)", 0)
    }

    private fun getCurrentDatabaseVersion(driver: SqlDriver): Long {
        val versionQuery = "SELECT version FROM schema_version ORDER BY version DESC LIMIT 1"

        val version = driver.executeQuery(null, versionQuery, parameters = 0, mapper = { cursor: SqlCursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
        })

        return version.value ?: 0
    }

    private fun updateDatabaseVersion(driver: SqlDriver, newVersion: Long) {
        val updateQuery = "INSERT INTO schema_version (version) VALUES (?)"
        driver.execute(null, updateQuery, 1) {
            bindLong(0, newVersion)
        }
    }
}
