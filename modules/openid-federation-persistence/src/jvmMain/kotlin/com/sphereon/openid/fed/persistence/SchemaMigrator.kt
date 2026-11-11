package com.sphereon.openid.fed.persistence

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import java.sql.SQLException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** What one [SchemaMigrator.migrate] call did. [appliedMigrations] is empty when the schema was already current. */
internal data class SchemaMigrationOutcome(
    val fromVersion: Long,
    val toVersion: Long,
    val appliedMigrations: List<Long>,
) {
    /** True when the database already held a recorded schema, which makes it an existing install rather than a fresh one. */
    val existingDatabase: Boolean get() = fromVersion > 0L
}

/** The schema could not be migrated. The message names the failing file, the state the database is in and what to do next. */
class SchemaMigrationException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** How long a start waits for the migration lock or for a table lock before it gives up and reports why. */
internal val DEFAULT_LOCK_TIMEOUT: Duration = 10.minutes

/** Fixed for every instance of every release that migrates a database (ASCII "OIDFSMIG"), so they all queue on one lock. */
internal const val SCHEMA_MIGRATION_LOCK_KEY = 0x4F49_4446_534D_4947L
private const val LOCK_NOT_AVAILABLE = "55P03"

/**
 * Brings the schema to [targetVersion] exactly once, however many instances start at the same time.
 *
 * Everything runs in one PostgreSQL transaction behind a transaction-scoped advisory lock. A second instance waits for the
 * lock and then finds nothing to do, and a failed statement or a lost connection rolls the database back to the version it
 * started from, so the next start retries from a clean state. [afterMigrationFile] runs inside the transaction after each
 * migration file and its history row, which lets a test stop a migration part way.
 */
internal class SchemaMigrator(
    private val driver: SqlDriver,
    private val targetVersion: Long = Database.Schema.version,
    private val lockTimeout: Duration = DEFAULT_LOCK_TIMEOUT,
    private val afterMigrationFile: (Long) -> Unit = {},
) {
    private enum class Step(val text: String) {
        LOCKING("waiting for the migration lock"),
        PREPARING("preparing the migration tables"),
        APPLYING("applying the migration files"),
        RECORDING("recording the new schema version"),
    }

    private class Progress {
        var step = Step.LOCKING
        var from: Long? = null
        var firstFile = 0L
        var lastAppliedFile: Long? = null
        var bodyCompleted = false
    }

    private class MigrationTransaction(driver: SqlDriver) : TransacterImpl(driver)

    fun migrate(): SchemaMigrationOutcome {
        val progress = Progress()
        try {
            return MigrationTransaction(driver).transactionWithResult { migrateInTransaction(progress) }
        } catch (refusal: SchemaMigrationException) {
            throw refusal
        } catch (failure: Exception) {
            throw failureReport(progress, failure)
        }
    }

    private fun migrateInTransaction(progress: Progress): SchemaMigrationOutcome {
        driver.update("SET LOCAL lock_timeout = ${lockTimeout.inWholeMilliseconds}")
        driver.query("SELECT pg_advisory_xact_lock($SCHEMA_MIGRATION_LOCK_KEY)") { }

        progress.step = Step.PREPARING
        createTrackingTables()
        val current = recordedVersion()
        progress.from = current
        when {
            current > targetVersion -> throw SchemaMigrationException(newerThanThisBuild(current, targetVersion))
            current == targetVersion -> return SchemaMigrationOutcome(current, current, emptyList())
            current == 0L && applicationTablesExist() -> throw SchemaMigrationException(tablesWithoutRecordedVersion())
        }
        if (current > 0L) {
            backfillHistory(current)
            repairShippedJwkShape()
        }

        val first = maxOf(1L, current)
        progress.firstFile = first
        progress.step = Step.APPLYING
        val callbacks = (first until targetVersion).map { file -> AfterVersion(file) { recordFile(file, progress) } }
        Database.Schema.migrate(driver, current, targetVersion, *callbacks.toTypedArray())

        progress.step = Step.RECORDING
        recordVersion(current)
        progress.bodyCompleted = true
        return SchemaMigrationOutcome(current, targetVersion, (first until targetVersion).toList())
    }

    private fun createTrackingTables() {
        driver.update("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
        driver.update("ALTER TABLE schema_version ADD COLUMN IF NOT EXISTS migrated_from INTEGER")
        driver.update("ALTER TABLE schema_version ADD COLUMN IF NOT EXISTS migrated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
        driver.update("ALTER TABLE schema_version ADD COLUMN IF NOT EXISTS description TEXT")
        driver.update(
            """CREATE TABLE IF NOT EXISTS schema_migration_history (
                id SERIAL PRIMARY KEY,
                version INTEGER NOT NULL,
                filename TEXT NOT NULL,
                description TEXT,
                applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                success BOOLEAN DEFAULT true
            )""",
        )
    }

    private fun recordedVersion(): Long =
        driver.query("SELECT version FROM schema_version ORDER BY version DESC LIMIT 1") { cursor ->
            if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L
        }

    /** Any table besides the two this runner keeps itself means the database was not created by this runner's schema. */
    private fun applicationTablesExist(): Boolean = driver.query(
        "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
            "AND lower(table_name) NOT IN ('schema_version', 'schema_migration_history')",
    ) { cursor -> cursor.next().value && (cursor.getLong(0) ?: 0L) > 0L }

    /** A database the old runner migrated has no per-file history, so every file below its version is recorded as applied. */
    private fun backfillHistory(current: Long) {
        val recorded = driver.query("SELECT count(*) FROM schema_migration_history") { cursor ->
            if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L
        }
        if (recorded > 0L) return
        for (file in 1L until current) {
            val (filename, description) = describeMigration(file)
            driver.execute(null, "INSERT INTO schema_migration_history (version, filename, description, applied_at) VALUES (?, ?, ?, NOW())", 3) {
                bindLong(0, file)
                bindString(1, filename)
                bindString(2, description)
            }
        }
    }

    /**
     * Releases up to 0.20.9-beta shipped a Jwk table without the alg column, because 2.sqm was edited after they went out.
     * A numbered file cannot add a column the derived schema already has, so the column is added here, before 29.sqm fills it.
     */
    private fun repairShippedJwkShape() {
        driver.update("ALTER TABLE IF EXISTS Jwk ADD COLUMN IF NOT EXISTS alg VARCHAR(255)")
    }

    private fun recordFile(file: Long, progress: Progress) {
        val (filename, description) = describeMigration(file)
        driver.execute(null, "INSERT INTO schema_migration_history (version, filename, description) VALUES (?, ?, ?)", 3) {
            bindLong(0, file)
            bindString(1, filename)
            bindString(2, description)
        }
        progress.lastAppliedFile = file
        afterMigrationFile(file)
    }

    private fun recordVersion(from: Long) {
        driver.execute(null, "INSERT INTO schema_version (version, migrated_from, description) VALUES (?, ?, ?)", 3) {
            bindLong(0, targetVersion)
            bindLong(1, from)
            bindString(2, "Migrated from version $from to $targetVersion")
        }
    }

    private fun failureReport(progress: Progress, cause: Exception): SchemaMigrationException {
        val chain = generateSequence<Throwable>(cause) { it.cause }.toList()
        if (progress.bodyCompleted) {
            return SchemaMigrationException(commitOutcomeUnknown(progress.from, targetVersion, cause), cause)
        }
        val state = progress.from?.let { "unchanged at schema version $it" } ?: "unchanged"
        val message = if (chain.any { it is SQLException && it.sqlState == LOCK_NOT_AVAILABLE }) {
            lockWaitGaveUp(lockTimeout.inWholeSeconds, state)
        } else {
            migrationFailed(progress.from, targetVersion, whereItFailed(progress), chain.last().message ?: cause.toString(), state)
        }
        return SchemaMigrationException(message, cause)
    }

    private fun whereItFailed(progress: Progress): String {
        if (progress.step != Step.APPLYING) return progress.step.text
        val (filename, description) = describeMigration((progress.lastAppliedFile ?: (progress.firstFile - 1)) + 1)
        return "applying $filename ($description)"
    }
}

private fun SqlDriver.update(sql: String) {
    execute(null, sql, 0)
}

private fun <T> SqlDriver.query(sql: String, read: (SqlCursor) -> T): T =
    executeQuery(identifier = null, sql = sql, mapper = { cursor -> QueryResult.Value(read(cursor)) }, parameters = 0).value
