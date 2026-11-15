package com.sphereon.openid.fed.persistence

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A migration that fails or is cut off must leave the source database exactly as it was, and a restart must finish the job. */
internal class SchemaMigratorRecoveryTest : MigrationTestBase() {

    /** 18.sqm creates this table without IF NOT EXISTS, so an incompatible one already in place fails it after 17.sqm has run. */
    private fun sourceWithBlockedTrustAnchorHintTable(): TestDatabase =
        sourceDatabase(SourceSchema.CLASS_A).also { it.execute("CREATE TABLE TrustAnchorHint (not_the_migrated_shape INTEGER)") }

    private fun assertSourceUntouched(database: TestDatabase, records: Map<String, String>, catalog: String) {
        assertEquals(listOf(listOf("${UpgradeFixtures.SOURCE_SCHEMA_VERSION}")), database.rows("SELECT version FROM schema_version"))
        assertFalse(database.columnExists("Account", "tenant_source"), "17.sqm ran before the failure and must have been rolled back")
        assertFalse(database.tableExists("schema_migration_history"), "tracking tables created by the failed run must be rolled back")
        assertEquals(records, UpgradeFixtures.manifest(database, UpgradeFixtures.PRESERVED))
        assertEquals(catalog, database.catalog())
    }

    @Test
    fun shouldLeaveTheDatabaseUnchangedAndNameTheFailingMigrationWhenOneFailsHalfway() {
        val database = sourceWithBlockedTrustAnchorHintTable()
        val records = UpgradeFixtures.manifest(database, UpgradeFixtures.PRESERVED)
        val catalog = database.catalog()

        val failure = assertFailsWith<SchemaMigrationException> { SchemaMigrator(database.driver).migrate() }

        val message = assertNotNull(failure.message)
        assertTrue("18.sqm" in message, "the failing file must be named: $message")
        assertTrue("schema version 17" in message && "unchanged" in message, "the message must say what state the database is in: $message")
        assertSourceUntouched(database, records, catalog)
    }

    @Test
    fun shouldCompleteTheUpgradeOnRetryOnceTheCauseIsRemoved() {
        val database = sourceWithBlockedTrustAnchorHintTable()
        val before = sourceManifest(database, SourceSchema.CLASS_A)
        assertFailsWith<SchemaMigrationException> { SchemaMigrator(database.driver).migrate() }

        database.execute("DROP TABLE TrustAnchorHint")
        val retry = SchemaMigrator(database.driver).migrate()

        assertEquals((UpgradeFixtures.SOURCE_SCHEMA_VERSION until targetVersion).toList(), retry.appliedMigrations)
        assertSourceRecordsUpgraded(database, before)
        assertEquals(
            (1 until targetVersion).toList(),
            database.rows("SELECT version FROM schema_migration_history ORDER BY version").map { it[0]!!.toLong() },
            "after the retry every file is recorded exactly once",
        )
    }

    @Test
    fun shouldRollBackWhenTheMigratingSessionIsTerminatedAndFinishOnRestart() {
        val database = sourceDatabase(SourceSchema.CLASS_A)
        val before = sourceManifest(database, SourceSchema.CLASS_A)
        val records = UpgradeFixtures.manifest(database, UpgradeFixtures.PRESERVED)
        val catalog = database.catalog()
        val parked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val migrating = thread(name = "interrupted-migration") {
            try {
                SchemaMigrator(database.driver, afterMigrationFile = { file ->
                    if (file == 20L) {
                        parked.countDown()
                        check(resume.await(60, TimeUnit.SECONDS)) { "The test never resumed the migration" }
                    }
                }).migrate()
            } catch (thrown: Throwable) {
                failure.set(thrown)
            }
        }
        assertTrue(parked.await(60, TimeUnit.SECONDS), "the migration never got as far as 20.sqm")

        // The migrating session is parked inside its open transaction, so it is the only one idle in a transaction.
        val killed = database.scalar(
            "SELECT count(*) FROM (SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                "WHERE datname = '${database.name}' AND state LIKE 'idle in transaction%') AS terminated",
        )
        assertEquals("1", killed)
        resume.countDown()
        migrating.join(60_000)

        assertFalse(migrating.isAlive, "the migrating thread must end once its session is gone")
        val thrown = failure.get()
        assertIs<SchemaMigrationException>(thrown, thrown?.toString())
        assertTrue("21.sqm" in assertNotNull(thrown.message), "the migration that could not run must be named: ${thrown.message}")
        database.reconnect()
        assertSourceUntouched(database, records, catalog)

        val restart = SchemaMigrator(database.driver).migrate()

        assertEquals((UpgradeFixtures.SOURCE_SCHEMA_VERSION until targetVersion).toList(), restart.appliedMigrations)
        assertSourceRecordsUpgraded(database, before)
    }
}
