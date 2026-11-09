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

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.sphereon.openid.fed.core.tenant.IdentityInstallState

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
    20L to ("20.sqm" to "Create ForeignSubordinateStatement table for received Immediate Superior JWTs"),
    21L to ("21.sqm" to "Create account-selected signing key binding with revision tracking"),
    22L to ("22.sqm" to "Create TrustMarkOwner table and the issuer's Trust Mark delegation per type"),
    23L to ("23.sqm" to "Separate historical key expiry, revocation reason tokens and operator descriptions"),
    24L to ("24.sqm" to "Persist account-role metadata-policy revision and operation fences"),
    25L to ("25.sqm" to "Record the last signing-key operation generation, token and request snapshot per account"),
    26L to ("26.sqm" to "Add versioned Trust Mark type definitions and their revision history"),
    27L to ("27.sqm" to "Administration document revisions, atomic publication artifacts and scheduled-key queue"),
    28L to ("28.sqm" to "Allow distinct logical JOSE keys to share an authorized physical key during rollover"),
)

/** Transaction runner over the migrating driver, so DDL and the advisory lock share one connection. */
private class MigrationTransacter(driver: SqlDriver) : TransacterImpl(driver)

/**
 * Advisory lock key ("OIDFMIGR") that serializes the migration across every process sharing one database.
 */
private const val MIGRATION_LOCK_KEY: Long = 0x4F494446_4D494752L

/**
 * Brings the schema to [Database.Schema.version].
 *
 * Several services, or several replicas, may boot against one empty database at once. The whole run therefore happens
 * in one transaction that first takes a transaction-scoped PostgreSQL advisory lock: the first process migrates, the
 * others wait, then find the schema current and change nothing. A failed run rolls back completely, so no
 * half-migrated schema is left behind.
 */
internal fun runMigrations(driver: SqlDriver) {
    val transacter = MigrationTransacter(driver)
    try {
        transacter.transaction { migrateUnderLock(driver, objectsAlreadyExist = false) }
    } catch (e: org.postgresql.util.PSQLException) {
        // A pre-existing schema without a version record (partial / hand-made installs): the objects are there,
        // so only the version is recorded. The failed attempt was rolled back, so this is a fresh transaction.
        if (e.message?.contains("already exists") == true) {
            transacter.transaction { migrateUnderLock(driver, objectsAlreadyExist = true) }
        } else {
            throw e
        }
    }
}

private fun migrateUnderLock(driver: SqlDriver, objectsAlreadyExist: Boolean) {
    driver.executeQuery(null, "SELECT pg_advisory_xact_lock(?)", parameters = 1, binders = { bindLong(0, MIGRATION_LOCK_KEY) }, mapper = { cursor: SqlCursor ->
        QueryResult.Value(cursor.next().value)
    })

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

    // Upgrade path: pre-existing schema (develop / pre-0.25 installs) → ACCOUNT mode default
    if (currentVersion > 0L) {
        IdentityInstallState.markExistingDatabase(currentVersion)
    } else if (legacyAccountTablePresent(driver)) {
        // Partial / older installs that have Account data but empty schema_version
        IdentityInstallState.markExistingDatabase(1L)
    }

    // Backfill migration history for existing databases that already have data
    backfillMigrationHistory(driver, currentVersion)

    val newVersion = Database.Schema.version
    if (currentVersion >= newVersion) return

    if (!objectsAlreadyExist) {
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
    }
    updateDatabaseVersion(driver, currentVersion, newVersion)
}

/**
 * Detect Account table from pre-migration / legacy databases so identity defaults to ACCOUNT.
 */
private fun legacyAccountTablePresent(driver: SqlDriver): Boolean {
    return try {
        val count = driver.executeQuery(
            null,
            """
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema = 'public' AND lower(table_name) = 'account'
            """.trimIndent(),
            parameters = 0,
            mapper = { cursor: SqlCursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else 0L)
            },
        ).value ?: 0L
        count > 0L
    } catch (_: Exception) {
        false
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
