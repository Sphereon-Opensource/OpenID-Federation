package com.sphereon.openid.fed.persistence

import com.sphereon.openid.fed.persistence.UpgradeFixtures.key
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Upgrades databases the way the old runner left them (schema version 17, built from the frozen shipped
 * migrations and seeded with records) and compares them with manifests computed inside PostgreSQL.
 */
internal class SchemaMigratorUpgradeTest : MigrationTestBase() {

    private class Upgraded(val database: TestDatabase, val before: Map<String, String>, val outcome: SchemaMigrationOutcome)

    private fun upgrade(schema: SourceSchema, prepare: (TestDatabase) -> Unit = {}): Upgraded {
        val database = sourceDatabase(schema).also(prepare)
        val before = sourceManifest(database, schema)
        return Upgraded(database, before, SchemaMigrator(database.driver).migrate())
    }

    @Test
    fun shouldPreserveEveryRecordWhenUpgradingSchemaClassA() = assertEveryRecordPreserved(SourceSchema.CLASS_A)

    @Test
    fun shouldPreserveEveryRecordWhenUpgradingSchemaClassB() = assertEveryRecordPreserved(SourceSchema.CLASS_B)

    private fun assertEveryRecordPreserved(schema: SourceSchema) {
        val upgraded = upgrade(schema)
        assertEquals(UpgradeFixtures.SOURCE_SCHEMA_VERSION, upgraded.outcome.fromVersion)
        assertEquals(targetVersion, upgraded.outcome.toVersion)
        assertEquals((UpgradeFixtures.SOURCE_SCHEMA_VERSION until targetVersion).toList(), upgraded.outcome.appliedMigrations)
        assertSourceRecordsUpgraded(upgraded.database, upgraded.before)
    }

    @Test
    fun shouldMintNoAccountOrKeyIdentityWhenUpgrading() {
        for (schema in SourceSchema.entries) {
            val database = upgrade(schema).database
            assertEquals("10", database.scalar("SELECT count(*) FROM Jwk"), "$schema key count")
            assertEquals("0", database.scalar("SELECT count(*) FROM Jwk WHERE id::text NOT LIKE 'b0000000-%'"), "$schema key ids")
            assertEquals("8", database.scalar("SELECT count(*) FROM Account"), "$schema account count")
            assertEquals("0", database.scalar("SELECT count(*) FROM Account WHERE id::text NOT LIKE 'a0000000-%'"), "$schema account ids")
        }
    }

    /**
     * The services publish only with a key an operator selected, so an upgrade must not choose one, not even for an account
     * whose active keys leave no doubt about which one the old release signed with. Six of the eight accounts hold an active key.
     */
    @Test
    fun shouldInferNoSigningKeySelectionFromExistingKeys() {
        for (schema in SourceSchema.entries) {
            val database = upgrade(schema).database
            assertEquals("0", database.scalar("SELECT count(*) FROM AccountSigningKey"), "$schema selection rows")
            assertEquals(
                "6",
                database.scalar("SELECT count(DISTINCT account_id) FROM Jwk WHERE revoked_at IS NULL"),
                "$schema accounts with an active key that stay unselected",
            )
        }
    }

    @Test
    fun shouldLowerCaseOnlyTheLegacyKmsNamesAndKeepEveryKeyReference() {
        for (schema in SourceSchema.entries) {
            val database = upgrade(schema) { it.execute("UPDATE Jwk SET kms = 'Custom-Provider' WHERE id = '${key(3)}'") }.database
            val kms = database.rows("SELECT id, kms FROM Jwk ORDER BY id").associate { it[0]!! to it[1]!! }
            val expected = mapOf(
                1 to "memory", 2 to "memory", 3 to "Custom-Provider", 4 to "memory", 5 to "azure",
                6 to "aws", 7 to "memory", 8 to "memory", 9 to "aws", 10 to "memory",
            ).mapKeys { key(it.key) }
            assertEquals(expected, kms, "$schema kms ids")
            assertEquals(
                "arn:aws:kms:eu-west-1:111122223333:key/11111111-2222-3333-4444-555555555555",
                database.scalar("SELECT kms_key_ref FROM Jwk WHERE id = '${key(6)}'"),
            )
        }
    }

    @Test
    fun shouldAddTheMissingAlgColumnToSchemaClassBAndFillEmptyAlgsFromThePublicKey() {
        val classB = sourceDatabase(SourceSchema.CLASS_B)
        assertFalse(classB.columnExists("Jwk", "alg"), "schema class B is the one without Jwk.alg")
        val classA = sourceDatabase(SourceSchema.CLASS_A)
        assertEquals("1", classA.scalar("SELECT count(*) FROM Jwk WHERE alg IS NULL"), "schema class A has one key with an empty alg")

        for (schema in SourceSchema.entries) {
            val database = upgrade(schema).database
            assertTrue(database.columnExists("Jwk", "alg"), "$schema Jwk.alg")
            assertEquals(listOf(listOf("10")), database.rows("SELECT count(*) FROM Jwk WHERE alg = 'ES256'"), "$schema alg values")
        }
    }

    @Test
    fun shouldReachTheCatalogOfAFreshInstallFromBothSchemaClasses() {
        val fresh = newDatabase().also { SchemaMigrator(it.driver).migrate() }
        for (schema in SourceSchema.entries) {
            assertEquals(fresh.catalog(), upgrade(schema).database.catalog(), "$schema catalog")
        }
    }

    @Test
    fun shouldRecordEveryMigrationFileOnceAndKeepTheSourceVersionRow() {
        val database = upgrade(SourceSchema.CLASS_A).database
        val history = database.rows("SELECT version, filename, success::text FROM schema_migration_history ORDER BY version")
        assertEquals((1 until targetVersion).map { listOf("$it", "$it.sqm", "true") }, history)
        assertEquals(
            listOf(listOf("17", null), listOf("$targetVersion", "17")),
            database.rows("SELECT version, migrated_from FROM schema_version ORDER BY version"),
        )
    }
}
