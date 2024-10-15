package com.sphereon.oid.fed.kms.local.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.sphereon.oid.fed.kms.local.Constants
import com.sphereon.oid.fed.kms.local.Database
import com.sphereon.oid.fed.kms.local.models.Keys


actual class LocalKmsDatabase {

    private var database: Database

    init {
        val driver = getDriver()
        runMigrations(driver)

        database = Database(driver)
    }

    private fun getDriver(): SqlDriver {
        return PlatformSqlDriver().createPostgresDriver(
            System.getenv(Constants.LOCAL_KMS_DATASOURCE_URL),
            System.getenv(Constants.LOCAL_KMS_DATASOURCE_USER),
            System.getenv(Constants.LOCAL_KMS_DATASOURCE_PASSWORD)
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

    actual fun getKey(keyId: String): Keys {
        return database.keysQueries.findById(keyId).executeAsOneOrNull()
            ?: throw KeyNotFoundException("$keyId not found")
    }

    actual fun insertKey(keyId: String, key: String) {
        database.keysQueries.create(keyId, key).executeAsOneOrNull()
    }

    actual fun deleteKey(keyId: String) {
        database.keysQueries.delete(keyId)
    }
}
