package com.sphereon.openid.fed.persistence

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A service that restarts, or starts against a database it cannot safely use, must neither replay nor guess. */
internal class SchemaMigratorRestartTest : MigrationTestBase() {

    /** Everything the runner writes about itself, including timestamps, so a rewritten row cannot hide. */
    private fun trackingState(database: TestDatabase): List<List<List<String?>>> = listOf(
        database.rows("SELECT version, migrated_from, migrated_at::text, description FROM schema_version ORDER BY version"),
        database.rows("SELECT id, version, filename, description, applied_at::text, success::text FROM schema_migration_history ORDER BY id"),
    )

    private fun assertRestartsDoNothing(database: TestDatabase) {
        val tracking = trackingState(database)
        val catalog = database.catalog()
        val records = UpgradeFixtures.manifest(database, UpgradeFixtures.PRESERVED)
        repeat(2) {
            val restart = SchemaMigrator(database.driver).migrate()
            assertEquals(targetVersion, restart.fromVersion)
            assertEquals(targetVersion, restart.toVersion)
            assertTrue(restart.appliedMigrations.isEmpty(), "a restart must not apply a migration again")
        }
        assertEquals(tracking, trackingState(database), "a restart must not touch the tracking tables")
        assertEquals(catalog, database.catalog(), "a restart must not change the schema")
        assertEquals(records, UpgradeFixtures.manifest(database, UpgradeFixtures.PRESERVED), "a restart must not change a record")
    }

    @Test
    fun shouldInstallAFreshDatabaseOnceAndDoNothingOnRestart() {
        val database = newDatabase()
        val first = SchemaMigrator(database.driver).migrate()
        assertEquals(0L, first.fromVersion)
        assertFalse(first.existingDatabase, "an empty database is a fresh install")
        assertEquals((1 until targetVersion).toList(), first.appliedMigrations)
        assertRestartsDoNothing(database)
    }

    @Test
    fun shouldDoNothingOnRestartOfAnUpgradedDatabaseFromEitherSchemaClass() {
        for (schema in SourceSchema.entries) {
            val database = sourceDatabase(schema)
            val upgrade = SchemaMigrator(database.driver).migrate()
            assertTrue(upgrade.existingDatabase, "$schema carries the old runner's version row, so it is an existing install")
            assertRestartsDoNothing(database)
        }
    }

    @Test
    fun shouldRefuseADatabaseWhoseSchemaIsNewerThanThisBuild() {
        val database = newDatabase().also { SchemaMigrator(it.driver).migrate() }
        val newer = targetVersion + 3
        database.execute("INSERT INTO schema_version (version) VALUES ($newer)")
        val tracking = trackingState(database)

        val failure = assertFailsWith<SchemaMigrationException> { SchemaMigrator(database.driver).migrate() }

        val message = assertNotNull(failure.message)
        assertTrue("schema version $newer" in message && "needs version $targetVersion" in message, message)
        assertEquals(tracking, trackingState(database), "a refusal must leave the tracking tables as they were")
    }

    @Test
    fun shouldRefuseAnyApplicationTableWithoutARecordedSchemaVersionNotOnlyAnAccountTable() {
        val database = newDatabase()
        database.execute("CREATE TABLE Jwk (id INTEGER PRIMARY KEY)")

        val failure = assertFailsWith<SchemaMigrationException> { SchemaMigrator(database.driver).migrate() }

        assertTrue("no recorded schema version" in assertNotNull(failure.message), failure.message)
        assertFalse(database.tableExists("Account"), "a refusal must create nothing")
    }

    @Test
    fun shouldRefuseTablesThatHaveNoRecordedSchemaVersionInsteadOfDeclaringThemCurrent() {
        val database = newDatabase()
        database.execute("CREATE TABLE Account (id UUID PRIMARY KEY)")

        val failure = assertFailsWith<SchemaMigrationException> { SchemaMigrator(database.driver).migrate() }

        assertTrue("no recorded schema version" in assertNotNull(failure.message), failure.message)
        assertTrue(database.tableExists("Account"), "the existing table must be left alone")
        assertFalse(database.tableExists("schema_version"), "a refusal must not create tracking tables")
        assertFalse(database.tableExists("TrustAnchorHint"), "a refusal must not migrate anything")
    }
}
