package com.sphereon.openid.fed.persistence

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A migration whose body ran to the end but whose commit failed cannot claim to know what the database holds. */
internal class SchemaMigratorCommitOutcomeTest : MigrationTestBase() {

    @Test
    fun shouldReportAnUnknownOutcomeInsteadOfARollbackWhenTheCommitItselfFails() {
        val database = sourceDatabase(SourceSchema.CLASS_A)
        database.executeAll(
            listOf(
                "CREATE FUNCTION refuse_commit() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'commit refused by the test'; END \$\$",
                "CREATE CONSTRAINT TRIGGER refuse_commit AFTER INSERT ON schema_version DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION refuse_commit()",
            ),
        )

        val failure = assertFailsWith<SchemaMigrationException> { SchemaMigrator(database.driver).migrate() }

        val message = assertNotNull(failure.message)
        assertTrue("may or may not have been committed" in message && "commit refused by the test" in message, message)
        assertTrue("rolled back" !in message, message)

        database.execute("DROP TRIGGER refuse_commit ON schema_version")
        assertEquals("${UpgradeFixtures.SOURCE_SCHEMA_VERSION}", database.scalar("SELECT max(version) FROM schema_version"))
        assertEquals(targetVersion, SchemaMigrator(database.driver).migrate().toVersion, "the next start applies the migration")
    }
}
