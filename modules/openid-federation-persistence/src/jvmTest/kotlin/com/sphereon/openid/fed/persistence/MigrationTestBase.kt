package com.sphereon.openid.fed.persistence

import org.junit.After
import org.junit.Rule
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/** Hands out throwaway databases and drops them after each test, whether it passed or failed. */
internal abstract class MigrationTestBase {
    private val databases = mutableListOf<TestDatabase>()

    /** A migration that blocks on a lock nobody releases must fail its test, not hang the build behind it. */
    @get:Rule
    val timeout: Timeout = Timeout(3, TimeUnit.MINUTES)

    protected val targetVersion: Long = Database.Schema.version

    protected fun newDatabase(): TestDatabase = SharedPostgres.createDatabase().also { databases += it }

    /** A database in the exact state the old runner left it in, with records. */
    protected fun sourceDatabase(schema: SourceSchema): TestDatabase =
        newDatabase().also { it.executeAll(UpgradeFixtures.sourceDatabase(schema)) }

    /** Source values captured before the upgrade, so the same manifest queries can be compared after it. */
    protected fun sourceManifest(database: TestDatabase, schema: SourceSchema): Map<String, String> =
        UpgradeFixtures.manifest(database, UpgradeFixtures.PRESERVED, UpgradeFixtures.EXPECTED_COMMON, schema.expectedAlgManifest)

    /**
     * Every preserved value is equal and every derived value is what the source rows require. The counts are
     * asserted first because a manifest that silently lost a query would make both loops pass over nothing.
     */
    protected fun assertSourceRecordsUpgraded(database: TestDatabase, before: Map<String, String>) {
        val after = UpgradeFixtures.manifest(database, UpgradeFixtures.PRESERVED, UpgradeFixtures.ACTUAL)
        val preserved = before.filterKeys { !it.startsWith("expected.") }
        val derived = before.filterKeys { it.startsWith("expected.") }
        assertEquals(UpgradeFixtures.PRESERVED_VALUES, preserved.size, "the preserved manifest must keep every query")
        assertEquals(UpgradeFixtures.EXPECTED_VALUES, derived.size, "the expected manifest must keep every query")
        preserved.forEach { (name, value) -> assertEquals(value, after[name], "$name changed during the upgrade") }
        derived.forEach { (name, value) ->
            assertEquals(value, after["actual." + name.removePrefix("expected.")], "$name differs from what the source rows require")
        }
    }

    @After
    fun dropDatabases() {
        databases.forEach(TestDatabase::close)
        databases.clear()
    }
}
