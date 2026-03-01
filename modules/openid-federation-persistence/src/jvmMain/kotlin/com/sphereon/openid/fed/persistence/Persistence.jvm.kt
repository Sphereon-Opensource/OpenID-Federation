package com.sphereon.openid.fed.persistence

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.sphereon.openid.fed.persistence.config.DatabaseConfig
import com.sphereon.openid.fed.persistence.database.JavaUuidStringAdapter
import com.sphereon.openid.fed.persistence.database.PlatformSqlDriver
import com.sphereon.openid.fed.persistence.models.Account
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.AuthorityHint
import com.sphereon.openid.fed.persistence.models.AuthorityHintQueries
import com.sphereon.openid.fed.persistence.models.Crit
import com.sphereon.openid.fed.persistence.models.CritQueries
import com.sphereon.openid.fed.persistence.models.EntityConfigurationStatement
import com.sphereon.openid.fed.persistence.models.EntityConfigurationStatementQueries
import com.sphereon.openid.fed.persistence.models.Jwk
import com.sphereon.openid.fed.persistence.models.JwkQueries
import com.sphereon.openid.fed.persistence.models.LogQueries
import com.sphereon.openid.fed.persistence.models.MetadataPolicy
import com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries
import com.sphereon.openid.fed.persistence.models.MetadataQueries
import com.sphereon.openid.fed.persistence.models.ReceivedTrustMark
import com.sphereon.openid.fed.persistence.models.ReceivedTrustMarkQueries
import com.sphereon.openid.fed.persistence.models.Subordinate
import com.sphereon.openid.fed.persistence.models.SubordinateJwk
import com.sphereon.openid.fed.persistence.models.SubordinateJwkQueries
import com.sphereon.openid.fed.persistence.models.SubordinateMetadata
import com.sphereon.openid.fed.persistence.models.SubordinateMetadataQueries
import com.sphereon.openid.fed.persistence.models.SubordinateQueries
import com.sphereon.openid.fed.persistence.models.SubordinateStatement
import com.sphereon.openid.fed.persistence.models.SubordinateStatementQueries
import com.sphereon.openid.fed.persistence.models.TrustMark
import com.sphereon.openid.fed.persistence.models.TrustMarkIssuer
import com.sphereon.openid.fed.persistence.models.TrustMarkIssuerQueries
import com.sphereon.openid.fed.persistence.models.TrustMarkQueries
import com.sphereon.openid.fed.persistence.models.TrustMarkType
import com.sphereon.openid.fed.persistence.models.TrustAnchorHint
import com.sphereon.openid.fed.persistence.models.TrustAnchorHintQueries
import com.sphereon.openid.fed.persistence.models.SubordinateConstraint
import com.sphereon.openid.fed.persistence.models.SubordinateConstraintQueries
import com.sphereon.openid.fed.persistence.models.TrustMarkTypeQueries

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
    actual val metadataPolicyQueries: MetadataPolicyQueries
    actual val trustAnchorHintQueries: TrustAnchorHintQueries
    actual val subordinateConstraintQueries: SubordinateConstraintQueries

    private val migrationDescriptions = mapOf(
        1L to ("1.sqm" to "Create Account table with username, identifier, timestamps, and soft-delete"),
        2L to ("2.sqm" to "Create Jwk table for storing JSON Web Keys with KMS references"),
        3L to ("3.sqm" to "Create Subordinate table for managing subordinate entities"),
        4L to ("4.sqm" to "Create EntityConfigurationStatement table for signed entity config JWTs"),
        5L to ("5.sqm" to "Create Metadata table for storing account-level metadata"),
        6L to ("6.sqm" to "Create AuthorityHint table for authority identifiers"),
        7L to ("7.sqm" to "Create Crit table for critical claims"),
        8L to ("8.sqm" to "Create SubordinateStatement table for signed statements about subordinates"),
        9L to ("9.sqm" to "Create SubordinateJwk table for subordinate entity JWKs"),
        10L to ("10.sqm" to "Create SubordinateMetadata table with unique constraints"),
        11L to ("11.sqm" to "Create TrustMarkType table for trust mark type definitions"),
        12L to ("12.sqm" to "Create TrustMarkIssuer table for authorized issuers"),
        13L to ("13.sqm" to "Create TrustMark table for issued trust marks with expiration"),
        14L to ("14.sqm" to "Create ReceivedTrustMark table for received trust mark JWTs"),
        15L to ("15.sqm" to "Create Log table for audit logging with severity tracking"),
        16L to ("16.sqm" to "Create MetadataPolicy table for JSON policy documents"),
        17L to ("17.sqm" to "Alter Account table to add tenant_source column"),
        18L to ("18.sqm" to "Create TrustAnchorHint table for trust anchor hint identifiers"),
        19L to ("19.sqm" to "Create SubordinateConstraint table for subordinate entity constraints"),
    )

    private val driver: SqlDriver
    private val database: Database

    init {
        driver = createDriver()
        runMigrations(driver)
        // We are mapping the Postgres UUID types to OpenAPI model string types
        database = Database(
            driver,
            AccountAdapter = Account.Adapter(JavaUuidStringAdapter),
            AuthorityHintAdapter = AuthorityHint.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            CritAdapter = Crit.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            EntityConfigurationStatementAdapter = EntityConfigurationStatement.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            JwkAdapter = Jwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            MetadataAdapter = com.sphereon.openid.fed.persistence.models.Metadata.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            ReceivedTrustMarkAdapter = ReceivedTrustMark.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            SubordinateAdapter = Subordinate.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            SubordinateJwkAdapter = SubordinateJwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            SubordinateMetadataAdapter = SubordinateMetadata.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            SubordinateStatementAdapter = SubordinateStatement.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            TrustMarkAdapter = TrustMark.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            TrustMarkIssuerAdapter = TrustMarkIssuer.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            TrustMarkTypeAdapter = TrustMarkType.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            MetadataPolicyAdapter = MetadataPolicy.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            TrustAnchorHintAdapter = TrustAnchorHint.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            SubordinateConstraintAdapter = SubordinateConstraint.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
        )

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
        metadataPolicyQueries = database.metadataPolicyQueries
        trustAnchorHintQueries = database.trustAnchorHintQueries
        subordinateConstraintQueries = database.subordinateConstraintQueries
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

        // Add tracking columns to schema_version (safe for existing DBs via IF NOT EXISTS)
        driver.execute(null, "ALTER TABLE schema_version ADD COLUMN IF NOT EXISTS migrated_from INTEGER", 0)
        driver.execute(null, "ALTER TABLE schema_version ADD COLUMN IF NOT EXISTS migrated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP", 0)
        driver.execute(null, "ALTER TABLE schema_version ADD COLUMN IF NOT EXISTS description TEXT", 0)

        // Create migration history table for per-file tracking
        driver.execute(
            null,
            """CREATE TABLE IF NOT EXISTS schema_migration_history (
                id SERIAL PRIMARY KEY,
                version INTEGER NOT NULL,
                filename TEXT NOT NULL,
                description TEXT,
                applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                success BOOLEAN DEFAULT true
            )""".trimIndent(),
            0
        )

        // Get current version
        val versionQuery = "SELECT version FROM schema_version ORDER BY version DESC LIMIT 1"
        val currentVersion = driver.executeQuery(null, versionQuery, parameters = 0, mapper = { cursor: SqlCursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else 0L)
        }).value ?: 0L

        // Backfill migration history for existing databases that already have data
        backfillMigrationHistory(driver, currentVersion)

        val newVersion = Database.Schema.version

        if (currentVersion < newVersion) {
            try {
                // Build AfterVersion callbacks to track each migration file.
                // AfterVersion(N) fires after N.sqm completes. Files run for N where
                // currentVersion <= N < newVersion. We skip N=0 since there's no 0.sqm.
                val firstVersion = maxOf(1L, currentVersion)
                val callbacks = (firstVersion until newVersion).map { version ->
                    AfterVersion(version) { drv ->
                        val (filename, desc) = migrationDescriptions[version]
                            ?: ("${version}.sqm" to "Unknown migration")
                        drv.execute(
                            null,
                            "INSERT INTO schema_migration_history (version, filename, description) VALUES (?, ?, ?)",
                            3
                        ) {
                            bindLong(0, version)
                            bindString(1, filename)
                            bindString(2, desc)
                        }
                    }
                }.toTypedArray()

                Database.Schema.migrate(driver, currentVersion, newVersion, *callbacks)

                updateDatabaseVersion(driver, currentVersion, newVersion)
            } catch (e: org.postgresql.util.PSQLException) {
                // If tables already exist, we can consider the schema as up-to-date
                if (e.message?.contains("already exists") == true) {
                    updateDatabaseVersion(driver, currentVersion, newVersion)
                } else {
                    throw e
                }
            }
        }
    }

    private fun backfillMigrationHistory(driver: SqlDriver, currentVersion: Long) {
        if (currentVersion <= 0) return

        val historyCount = driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM schema_migration_history",
            parameters = 0,
            mapper = { cursor: SqlCursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else 0L)
            }
        ).value ?: 0L

        if (historyCount > 0) return

        // Backfill: insert synthetic rows for all previously applied migrations.
        // When version V is stored, files 1.sqm through (V-1).sqm have been applied.
        for (v in 1 until currentVersion) {
            val (filename, desc) = migrationDescriptions[v] ?: ("${v}.sqm" to "Unknown migration")
            driver.execute(
                null,
                "INSERT INTO schema_migration_history (version, filename, description, applied_at) VALUES (?, ?, ?, NOW())",
                3
            ) {
                bindLong(0, v)
                bindString(1, filename)
                bindString(2, desc)
            }
        }
    }

    private fun updateDatabaseVersion(driver: SqlDriver, oldVersion: Long, newVersion: Long) {
        driver.execute(
            null,
            "INSERT INTO schema_version (version, migrated_from, description) VALUES (?, ?, ?)",
            3
        ) {
            bindLong(0, newVersion)
            bindLong(1, oldVersion)
            bindString(2, "Migrated from version $oldVersion to $newVersion")
        }
    }
}
