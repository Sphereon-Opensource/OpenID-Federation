package com.sphereon.oid.fed.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.sphereon.oid.fed.persistence.database.PlatformSqlDriver
import com.sphereon.oid.fed.persistence.models.AccountQueries
import com.sphereon.oid.fed.persistence.models.AuthorityHintQueries
import com.sphereon.oid.fed.persistence.models.CritQueries
import com.sphereon.oid.fed.persistence.models.EntityConfigurationMetadataQueries
import com.sphereon.oid.fed.persistence.models.EntityConfigurationStatementQueries
import com.sphereon.oid.fed.persistence.models.KeyQueries
import com.sphereon.oid.fed.persistence.models.LogQueries
import com.sphereon.oid.fed.persistence.models.ReceivedTrustMarkQueries
import com.sphereon.oid.fed.persistence.models.SubordinateJwkQueries
import com.sphereon.oid.fed.persistence.models.SubordinateMetadataQueries
import com.sphereon.oid.fed.persistence.models.SubordinateQueries
import com.sphereon.oid.fed.persistence.models.SubordinateStatementQueries
import com.sphereon.oid.fed.persistence.models.TrustMarkIssuerQueries
import com.sphereon.oid.fed.persistence.models.TrustMarkQueries
import com.sphereon.oid.fed.persistence.models.TrustMarkTypeQueries

actual object Persistence {
    actual val entityConfigurationStatementQueries: EntityConfigurationStatementQueries
    actual val accountQueries: AccountQueries
    actual val keyQueries: KeyQueries
    actual val subordinateQueries: SubordinateQueries
    actual val entityConfigurationMetadataQueries: EntityConfigurationMetadataQueries
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

    init {
        val driver = getDriver()
        runMigrations(driver)

        val database = Database(driver)
        accountQueries = database.accountQueries
        entityConfigurationStatementQueries = database.entityConfigurationStatementQueries
        keyQueries = database.keyQueries
        subordinateQueries = database.subordinateQueries
        entityConfigurationMetadataQueries = database.entityConfigurationMetadataQueries
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

    private fun getDriver(): SqlDriver {
        return PlatformSqlDriver().createPostgresDriver(
            System.getenv(Constants.DATASOURCE_URL),
            System.getenv(Constants.DATASOURCE_USER),
            System.getenv(Constants.DATASOURCE_PASSWORD)
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
                // Update version
                val updateQuery = "INSERT INTO schema_version (version) VALUES (?)"
                driver.execute(null, updateQuery, 1) {
                    bindLong(0, newVersion)
                }
            } catch (e: org.postgresql.util.PSQLException) {
                // If tables already exist, we can consider the schema as up-to-date
                if (e.message?.contains("already exists") == true) {
                    val updateQuery = "INSERT INTO schema_version (version) VALUES (?)"
                    driver.execute(null, updateQuery, 1) {
                        bindLong(0, newVersion)
                    }
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
