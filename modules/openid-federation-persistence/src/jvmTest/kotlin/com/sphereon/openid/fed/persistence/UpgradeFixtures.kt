package com.sphereon.openid.fed.persistence

/** The two schema classes an official release can leave behind. Both record schema version 17. */
internal enum class SourceSchema(private val jwkSeed: String, private val shippedTwoSqm: String, val expectedAlgManifest: String) {
    /** Official 0.23.0 up to 0.23.1-beta.7f6c091. `Jwk.alg` exists, one key has it empty. */
    CLASS_A("02-jwk-row-a.sql", "frozen/v0.23.0/2.sqm", "expected-alg-row-a.sql"),

    /** Official 0.16.8 up to 0.20.7. `2.sqm` was edited after they shipped, so `Jwk.alg` is missing. */
    CLASS_B("02-jwk-row-b.sql", "frozen/v0.20.7/2.sqm", "expected-alg-row-b.sql"),
    ;

    fun migration(number: Int): String =
        UpgradeFixtures.resource(if (number == 2) shippedTwoSqm else "frozen/v0.23.0/$number.sqm")

    fun seeds(): List<String> =
        listOf("01-accounts.sql", jwkSeed, "03-entity-data.sql").flatMap { UpgradeFixtures.statements(UpgradeFixtures.resource("seed/$it")) }
}

/** Loads the immutable upgrade fixtures from the test resources and turns them into executable statements. */
internal object UpgradeFixtures {
    /** The version row the old runner left after applying `1.sqm` to `16.sqm`. */
    const val SOURCE_SCHEMA_VERSION = 17L

    const val PRESERVED = "preserved.sql"
    const val EXPECTED_COMMON = "expected-common.sql"
    const val ACTUAL = "actual.sql"

    /** Distinct `name` values the preserved manifest produces; a manifest that drops one fails the suite. */
    const val PRESERVED_VALUES = 37

    /** Distinct `expected.*` values for one source schema, and the same number of `actual.*` values after the upgrade. */
    const val EXPECTED_VALUES = 7

    fun resource(path: String): String {
        val stream = UpgradeFixtures::class.java.classLoader.getResourceAsStream("upgrade/$path")
            ?: error("The upgrade fixture upgrade/$path is missing from the test resources")
        return stream.use { String(it.readBytes(), Charsets.UTF_8) }.replace("\r\n", "\n")
    }

    /** The statements that leave a database exactly as the old runner did after it migrated to [SOURCE_SCHEMA_VERSION], with records. */
    fun sourceDatabase(schema: SourceSchema): List<String> = buildList {
        add("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
        for (number in 1..16) addAll(statements(sqlDelightToSql(schema.migration(number))))
        add("INSERT INTO schema_version (version) VALUES ($SOURCE_SCHEMA_VERSION)")
        addAll(schema.seeds())
    }

    /** Runs each manifest file against [database] and merges the `name` and `value` rows they return. */
    fun manifest(database: TestDatabase, vararg files: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        for (file in files) {
            for (query in statements(resource("manifest/$file"))) {
                val row = database.rows(query).single()
                check(values.put(row[0]!!, row[1]!!) == null) { "The manifest produces ${row[0]} more than once" }
            }
        }
        return values
    }

    /** SQLDelight migration sources carry an import line and typed columns that PostgreSQL does not know. */
    fun sqlDelightToSql(source: String): String =
        source.lineSequence().filterNot { it.startsWith("import ") }.joinToString("\n").replace(" AS kotlin.String", "")

    /** Splits on semicolons outside single-quoted strings and drops line comments. */
    fun statements(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var index = 0
        while (index < sql.length) {
            val c = sql[index]
            when {
                inString -> { current.append(c); if (c == '\'') inString = false }
                c == '\'' -> { current.append(c); inString = true }
                c == '-' && sql.startsWith("--", index) -> index = sql.indexOf('\n', index).let { if (it < 0) sql.length else it - 1 }
                c == ';' -> { current.toString().trim().takeIf { it.isNotEmpty() }?.let(statements::add); current.clear() }
                else -> current.append(c)
            }
            index++
        }
        current.toString().trim().takeIf { it.isNotEmpty() }?.let(statements::add)
        return statements
    }

    fun account(number: Int): String = "a0000000-0000-4000-8000-%012d".format(number)

    fun key(number: Int): String = "b0000000-0000-4000-8000-%012d".format(number)
}
