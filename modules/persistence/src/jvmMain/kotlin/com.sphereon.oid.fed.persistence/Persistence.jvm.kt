package com.sphereon.oid.fed.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.sphereon.oid.fed.persistence.config.DatabaseConfig
import com.sphereon.oid.fed.persistence.database.PlatformSqlDriver
import com.sphereon.oid.fed.persistence.models.*

/**
 * Platform-specific implementation of the persistence layer for JVM.
 * Handles database connections, migrations, and provides query interfaces for all entities.
 */
actual object Persistence {
    actual val entityConfigurationStatementQueries: EntityConfigurationStatementQueries
    actual val accountQueries: AccountQueries
    actual val jwkQueries: JwkQueries
    actual val subordinateQueries: SubordinateQueries
    actual val metadataQueries: MetadataQueries
    actual val authorityHintQueries: AuthorityHintQueries
    actual val critQueries: CritQueries
    actual val subordinateStatementQueries: SubordinateStatementQueries
    actual val subordinateJwkQueries: SubordinateJwkQueries
    actual val subordinateMetadataQueries: SubordinateMetadataQueries
    actual val trustMarkTypeQueries: TrustMarkTypeQueries
    actual val trustMarkIssuerQueries: TrustMarkIssuerQueries
    actual val trustMarkQueries: TrustMarkQueries
    actual val receivedTrustMarkQueries: ReceivedTrustMarkQueries
    actual val logQueries: LogQueries

    private val driver: SqlDriver
    private val database: Database

    init {
        driver = createDriver()
        runMigrations(driver)
        database = Database(driver)

        accountQueries = database.accountQueries
        entityConfigurationStatementQueries = database.entityConfigurationStatementQueries
        jwkQueries = database.jwkQueries
        subordinateQueries = database.subordinateQueries
        metadataQueries = database.metadataQueries
        authorityHintQueries = database.authorityHintQueries
        critQueries = database.critQueries
        subordinateStatementQueries = database.subordinateStatementQueries
        subordinateJwkQueries = database.subordinateJwkQueries
        subordinateMetadataQueries = database.subordinateMetadataQueries
        trustMarkTypeQueries = database.trustMarkTypeQueries
        trustMarkIssuerQueries = database.trustMarkIssuerQueries
        trustMarkQueries = database.trustMarkQueries
        receivedTrustMarkQueries = database.receivedTrustMarkQueries
        logQueries = database.logQueries
    }

    private fun createDriver(): SqlDriver {
        val config = DatabaseConfig()
        return PlatformSqlDriver().createPostgresDriver(
            config.url,
            config.username,
            config.password
        )
    }

    private fun runMigrations(driver: SqlDriver) {
        // Create schema version table if it doesn't exist
        driver.execute(null, "CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)", 0)

        // Get current version
        val versionQuery = "SELECT version FROM schema_version ORDER BY version DESC LIMIT 1"
        val currentVersion = driver.executeQuery(null, versionQuery, parameters = 0, mapper = { cursor: SqlCursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else 0L)
        }).value ?: 0L

        val newVersion = Database.Schema.version

        if (currentVersion < newVersion) {
            try {
                Database.Schema.migrate(driver, currentVersion, newVersion)
                updateDatabaseVersion(driver, newVersion)
            } catch (e: org.postgresql.util.PSQLException) {
                // If tables already exist, we can consider the schema as up-to-date
                if (e.message?.contains("already exists") == true) {
                    updateDatabaseVersion(driver, newVersion)
                } else {
                    throw e
                }
            }
        }
    }

    private fun updateDatabaseVersion(driver: SqlDriver, newVersion: Long) {
        val updateQuery = "INSERT INTO schema_version (version) VALUES (?)"
        driver.execute(null, updateQuery, 1) {
            bindLong(0, newVersion)
        }
    }
}