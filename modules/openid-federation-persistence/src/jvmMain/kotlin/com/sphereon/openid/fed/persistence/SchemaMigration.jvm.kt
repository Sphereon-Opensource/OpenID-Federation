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
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.sphereon.openid.fed.core.tenant.IdentityInstallState
import java.sql.SQLException

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
 * How [runMigrations] retries. Each attempt is one whole transaction, so a failed attempt leaves nothing behind.
 *
 * @property maxAttempts attempts before giving up with a clear error
 * @property lockTimeoutMillis longest a statement may wait for a lock, the migration lock included
 * @property backoffMillis pause after attempt n is `n * backoffMillis`
 * @property pause how to pause; replaced in tests
 */
internal class MigrationRetryPolicy(
    val maxAttempts: Int = 5,
    val lockTimeoutMillis: Long = 60_000L,
    val backoffMillis: Long = 500L,
    val pause: (Long) -> Unit = { millis -> Thread.sleep(millis) },
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }
}

/**
 * Advisory lock key ("OIDFMIGR") that serializes the migration across every process sharing one database.
 */
private const val MIGRATION_LOCK_KEY: Long = 0x4F494446_4D494752L

/**
 * SQLSTATEs that say the attempt lost a race or a lock, not that the migration is wrong: internal_error (for example
 * "tuple concurrently updated" when another session changes the same catalog rows), deadlock_detected,
 * serialization_failure, unique_violation (catalog races on `CREATE ... IF NOT EXISTS`) and lock_not_available
 * (the lock timeout).
 */
private val TRANSIENT_SQL_STATES = setOf("XX000", "40P01", "40001", "23505", "55P03")

private fun sqlStateOf(failure: Throwable): String? {
    var current: Throwable? = failure
    var depth = 0
    while (current != null && depth < 10) {
        if (current is SQLException && current.sqlState != null) return current.sqlState
        current = current.cause
        depth++
    }
    return null
}

/**
 * Brings the schema to [Database.Schema.version].
 *
 * Several services, or several replicas, may boot against one empty database at once. Each attempt therefore runs in
 * one transaction that first takes a transaction-scoped PostgreSQL advisory lock: the first process migrates, the
 * others wait, then find the schema current and change nothing. A failed attempt rolls back completely, so no
 * half-migrated schema is left behind. Failures that mean a lost race (see [TRANSIENT_SQL_STATES]) retry the whole
 * transaction up to [MigrationRetryPolicy.maxAttempts] times; any other failure is thrown at once.
 *
 * A schema without a version record that already holds an Account table (a pre-versioning install) is adopted only
 * when its shape equals a version of this library's schema (see [adoptedVersion]); the later files then run in order.
 * A schema that matches no version is refused with the differences listed, and nothing is changed. No file is ever
 * skipped.
 *
 * @throws IllegalStateException when every attempt failed with a transient error; the last one is the cause
 */
internal fun runMigrations(driver: SqlDriver, policy: MigrationRetryPolicy = MigrationRetryPolicy()) {
    val transacter = MigrationTransacter(driver)
    var lastFailure: Throwable? = null
    for (attempt in 1..policy.maxAttempts) {
        try {
            transacter.transaction { migrateUnderLock(driver, policy.lockTimeoutMillis) }
            return
        } catch (failure: Exception) {
            val sqlState = sqlStateOf(failure)
            if (sqlState == null || sqlState !in TRANSIENT_SQL_STATES) throw failure
            lastFailure = failure
            if (attempt < policy.maxAttempts) policy.pause(policy.backoffMillis * attempt)
        }
    }
    throw IllegalStateException(
        "The OpenID Federation schema migration failed ${policy.maxAttempts} times with a database error that can be a " +
            "lost race or a real fault (SQLSTATE ${lastFailure?.let { sqlStateOf(it) }}: ${lastFailure?.message}). Each attempt was rolled back, " +
            "so the database is unchanged. The cause carries the database's own message.",
        lastFailure,
    )
}

private fun migrateUnderLock(driver: SqlDriver, lockTimeoutMillis: Long) {
    driver.execute(null, "SET LOCAL lock_timeout = '${lockTimeoutMillis}ms'", 0)
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

    val newVersion = Database.Schema.version

    // Upgrade path: pre-existing schema (develop / pre-0.25 installs) → ACCOUNT mode default
    var startVersion = currentVersion
    if (currentVersion > 0L) {
        IdentityInstallState.markExistingDatabase(currentVersion)
    } else if (legacyAccountTablePresent(driver)) {
        // Partial / older installs that have Account data but empty schema_version
        IdentityInstallState.markExistingDatabase(1L)
        startVersion = adoptedVersion(driver, newVersion)
    }

    // Backfill migration history for existing databases that already have data
    backfillMigrationHistory(driver, startVersion)
    if (currentVersion >= newVersion) return

    // Files run for N where startVersion <= N < newVersion; there is no 0.sqm.
    for (step in maxOf(1L, startVersion) until newVersion) {
        Database.Schema.migrate(driver, step, step + 1)
        val (filename, description) = migrationDescriptions[step] ?: ("${step}.sqm" to "Unknown migration")
        driver.execute(
            null,
            "INSERT INTO schema_migration_history (version, filename, description) VALUES (?, ?, ?)",
            3
        ) {
            bindLong(0, step)
            bindString(1, filename)
            bindString(2, description)
        }
    }
    updateDatabaseVersion(driver, startVersion, newVersion)
}

/**
 * Finds which version an unversioned schema with an Account table really is at, or refuses to adopt it.
 *
 * Builds a reference schema inside this transaction (a uniquely named schema, `search_path` pointing at it), applies
 * the migration files to it one at a time, and compares the live schema's shape (see [captureSchemaCatalog]) with the
 * reference after each file. The highest version whose shape equals the live schema is returned; the caller then runs
 * only the later files, so no file is ever skipped or half-applied. When no version matches, the differences against
 * the latest shape are listed in the error and nothing is stamped, so the transaction rolls back.
 *
 * A shape match proves the DDL, not the data: if a file's backfill statements never ran on a schema whose DDL is
 * complete, no catalog comparison can see it.
 *
 * @return the version V such that files 1 until V are present
 */
private fun adoptedVersion(driver: SqlDriver, newVersion: Long): Long {
    val liveSchema = driver.executeQuery(null, "SELECT current_schema()", parameters = 0, mapper = { cursor: SqlCursor ->
        QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
    }).value ?: "public"
    val originalSearchPath = driver.executeQuery(null, "SELECT current_setting('search_path')", parameters = 0, mapper = { cursor: SqlCursor ->
        QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
    }).value ?: liveSchema
    val referenceSchema = "oidf_adopt_ref_" + java.util.UUID.randomUUID().toString().replace("-", "")
    val live = captureSchemaCatalog(driver, liveSchema)

    driver.execute(null, "CREATE SCHEMA $referenceSchema", 0)
    driver.execute(null, "SELECT set_config('search_path', '$referenceSchema', true)", 0)
    var matched: Long? = null
    var latestShape: List<String> = emptyList()
    for (step in 1L until newVersion) {
        Database.Schema.migrate(driver, step, step + 1)
        latestShape = captureSchemaCatalog(driver, referenceSchema)
        if (latestShape == live) matched = step + 1
    }
    driver.execute(null, "SELECT set_config('search_path', '${originalSearchPath.replace("'", "''")}', true)", 0)
    driver.execute(null, "DROP SCHEMA $referenceSchema CASCADE", 0)

    return matched ?: throw IllegalStateException(
        "The existing OpenID Federation schema has no version record and does not match any version of this library, " +
            "so it was not adopted and nothing was changed. Differences against the latest version:\n" +
            describeCatalogDifferences(latestShape, live).joinToString("\n"),
    )
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
    for (appliedVersion in 1 until currentVersion) {
        val (filename, desc) = migrationDescriptions[appliedVersion] ?: ("${appliedVersion}.sqm" to "Unknown migration")
        driver.execute(
            null,
            "INSERT INTO schema_migration_history (version, filename, description, applied_at) VALUES (?, ?, ?, NOW())",
            3
        ) {
            bindLong(0, appliedVersion)
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
