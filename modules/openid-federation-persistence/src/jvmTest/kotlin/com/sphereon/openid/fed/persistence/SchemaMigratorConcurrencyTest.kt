package com.sphereon.openid.fed.persistence

import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Instances of a service that start together against one database. The starters are parked behind a latch until all of
 * them are ready, so they genuinely overlap. The assertions count who applied the migrations and not only who
 * succeeded, because a runner that checks the version and then migrates also lets every start succeed.
 */
internal class SchemaMigratorConcurrencyTest : MigrationTestBase() {
    private val starters = 6

    private fun migrateTogether(database: TestDatabase): List<SchemaMigrationOutcome> {
        val ready = CountDownLatch(starters)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(starters)
        try {
            val starts = (1..starters).map {
                pool.submit(Callable {
                    ready.countDown()
                    check(gate.await(60, TimeUnit.SECONDS)) { "The starters were never released" }
                    SchemaMigrator(database.driver).migrate()
                })
            }
            assertTrue(ready.await(60, TimeUnit.SECONDS), "every starter must be waiting at the gate before any is released")
            gate.countDown()
            return starts.map { it.get(120, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun assertExactlyOneOwner(outcomes: List<SchemaMigrationOutcome>, sourceVersion: Long) {
        assertEquals(starters, outcomes.size)
        val owners = outcomes.filter { it.appliedMigrations.isNotEmpty() }
        assertEquals(1, owners.size, "exactly one start may apply the migrations: $outcomes")
        assertEquals((maxOf(1L, sourceVersion) until targetVersion).toList(), owners.single().appliedMigrations)
        outcomes.filter { it.appliedMigrations.isEmpty() }.forEach {
            assertEquals(targetVersion, it.fromVersion, "a start that waited must find the schema already current")
            assertEquals(targetVersion, it.toVersion)
        }
    }

    private fun assertAppliedOnce(database: TestDatabase, versionRows: Int) {
        assertEquals("$versionRows", database.scalar("SELECT count(*) FROM schema_version"))
        assertEquals(
            (1 until targetVersion).map { "$it" },
            database.rows("SELECT version FROM schema_migration_history ORDER BY version").map { it[0] },
            "every migration file must be recorded exactly once",
        )
    }

    @Test
    fun shouldLetExactlyOneOfSeveralSimultaneousStartsUpgradeASourceDatabase() {
        for (schema in SourceSchema.entries) {
            val database = sourceDatabase(schema)
            val before = sourceManifest(database, schema)

            assertExactlyOneOwner(migrateTogether(database), UpgradeFixtures.SOURCE_SCHEMA_VERSION)

            assertAppliedOnce(database, versionRows = 2)
            assertEquals("0", database.scalar("SELECT count(*) FROM AccountSigningKey"), "$schema: a start must not infer a selection")
            assertSourceRecordsUpgraded(database, before)
        }
    }

    @Test
    fun shouldLetExactlyOneOfSeveralSimultaneousStartsInstallAFreshDatabase() {
        val database = newDatabase()

        assertExactlyOneOwner(migrateTogether(database), sourceVersion = 0L)

        assertAppliedOnce(database, versionRows = 1)
        assertEquals(newDatabase().also { SchemaMigrator(it.driver).migrate() }.catalog(), database.catalog())
    }

    @Test
    fun shouldMakeASecondStartWaitForTheOwnerAndThenFindNothingToDo() {
        val database = sourceDatabase(SourceSchema.CLASS_A)
        val before = sourceManifest(database, SourceSchema.CLASS_A)
        val parked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val owner = pool.submit(Callable {
                SchemaMigrator(database.driver, afterMigrationFile = { file ->
                    if (file == 20L) {
                        parked.countDown()
                        check(resume.await(60, TimeUnit.SECONDS)) { "The test never resumed the owner" }
                    }
                }).migrate()
            })
            assertTrue(parked.await(60, TimeUnit.SECONDS), "the owner never got as far as 20.sqm")
            val second = pool.submit(Callable { SchemaMigrator(database.driver).migrate() })
            awaitAStartWaitingForTheMigrationLock(database)
            assertFalse(second.isDone, "a second start must wait while the owner holds the lock")

            resume.countDown()
            val ownerOutcome = owner.get(120, TimeUnit.SECONDS)
            val secondOutcome = second.get(120, TimeUnit.SECONDS)

            assertEquals((UpgradeFixtures.SOURCE_SCHEMA_VERSION until targetVersion).toList(), ownerOutcome.appliedMigrations)
            assertTrue(secondOutcome.appliedMigrations.isEmpty(), "the second start must not apply anything: $secondOutcome")
            assertEquals(targetVersion, secondOutcome.fromVersion)
        } finally {
            resume.countDown()
            pool.shutdownNow()
        }
        assertAppliedOnce(database, versionRows = 2)
        assertSourceRecordsUpgraded(database, before)
    }

    @Test
    fun shouldGiveUpWithAnActionableMessageWhenTheMigrationLockStaysHeld() {
        val database = sourceDatabase(SourceSchema.CLASS_A)
        val before = sourceManifest(database, SourceSchema.CLASS_A)

        hold(database, "SELECT pg_advisory_xact_lock($SCHEMA_MIGRATION_LOCK_KEY)").use {
            val failure = assertFailsWith<SchemaMigrationException> { SchemaMigrator(database.driver, lockTimeout = 2.seconds).migrate() }
            assertGaveUpWaiting(failure)
            // Nothing ran before the lock was granted, so reading the version row cannot block.
            assertEquals(listOf(listOf("${UpgradeFixtures.SOURCE_SCHEMA_VERSION}")), database.rows("SELECT version FROM schema_version"))
            assertFalse(database.columnExists("Account", "tenant_source"))
        }

        SchemaMigrator(database.driver).migrate()
        assertSourceRecordsUpgraded(database, before)
    }

    @Test
    fun shouldGiveUpWithAnActionableMessageWhenATableLockStaysHeld() {
        val database = sourceDatabase(SourceSchema.CLASS_A)
        val before = sourceManifest(database, SourceSchema.CLASS_A)

        hold(database, "LOCK TABLE Jwk IN ACCESS EXCLUSIVE MODE").use {
            val failure = assertFailsWith<SchemaMigrationException> { SchemaMigrator(database.driver, lockTimeout = 2.seconds).migrate() }
            assertGaveUpWaiting(failure)
            assertEquals(listOf(listOf("${UpgradeFixtures.SOURCE_SCHEMA_VERSION}")), database.rows("SELECT version FROM schema_version"))
            assertFalse(database.tableExists("schema_migration_history"), "the failed start must roll back its tracking tables")
        }

        SchemaMigrator(database.driver).migrate()
        assertSourceRecordsUpgraded(database, before)
    }

    private fun assertGaveUpWaiting(failure: SchemaMigrationException) {
        val message = assertNotNull(failure.message)
        assertTrue("gave up after waiting 2 seconds for a lock" in message && "rolled back" in message, message)
    }

    /** Runs [statement] in a transaction that stays open until the returned handle is closed. */
    private fun hold(database: TestDatabase, statement: String): AutoCloseable {
        val connection = database.dataSource.connection
        connection.autoCommit = false
        connection.createStatement().use { it.execute(statement) }
        return AutoCloseable {
            connection.commit()
            connection.close()
        }
    }

    private fun awaitAStartWaitingForTheMigrationLock(database: TestDatabase) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            val waiting = database.scalar(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = '${database.name}' " +
                    "AND wait_event_type = 'Lock' AND wait_event = 'advisory'",
            )
            if (waiting == "1") return
            Thread.sleep(20)
        }
        fail("No start began waiting for the migration lock")
    }
}
