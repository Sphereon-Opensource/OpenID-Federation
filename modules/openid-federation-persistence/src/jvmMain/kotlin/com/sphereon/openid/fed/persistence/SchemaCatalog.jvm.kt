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

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver

/** Bookkeeping tables are not part of the schema shape that is compared. */
private val BOOKKEEPING_TABLES = setOf("schema_version", "schema_migration_history")

/**
 * The shape of one PostgreSQL schema as sorted, comparable lines: columns (type, nullability, default, length),
 * indexes, foreign keys and check constraints. Schema names are stripped, so two schemas with the same shape give the
 * same lines. Row data is not part of it.
 */
internal fun captureSchemaCatalog(driver: SqlDriver, schema: String): List<String> {
    val excluded = BOOKKEEPING_TABLES.joinToString { "'$it'" }
    val lines = mutableListOf<String>()
    lines += queryLines(
        driver, schema,
        """
        SELECT table_name, column_name, udt_name, is_nullable, column_default, character_maximum_length
        FROM information_schema.columns
        WHERE table_schema = ? AND table_name NOT IN ($excluded)
        """.trimIndent(),
    ) { cursor ->
        "col|${cursor.getString(0)}|${cursor.getString(1)}|${cursor.getString(2)}|${cursor.getString(3)}|" +
            "${normalize(cursor.getString(4), schema)}|${cursor.getLong(5) ?: ""}"
    }
    lines += queryLines(
        driver, schema,
        "SELECT tablename, indexname, indexdef FROM pg_indexes WHERE schemaname = ? AND tablename NOT IN ($excluded)",
    ) { cursor -> "idx|${cursor.getString(0)}|${cursor.getString(1)}|${normalize(cursor.getString(2), schema)}" }
    lines += queryLines(
        driver, schema,
        """
        SELECT rel.relname, con.conname, pg_get_constraintdef(con.oid, true)
        FROM pg_catalog.pg_constraint con
        JOIN pg_catalog.pg_class rel ON rel.oid = con.conrelid
        JOIN pg_catalog.pg_namespace ns ON ns.oid = rel.relnamespace
        WHERE ns.nspname = ? AND con.contype IN ('f', 'c', 'u', 'p') AND rel.relkind IN ('r', 'p')
          AND rel.relname NOT IN ($excluded) AND con.conname NOT LIKE '%_not_null'
        """.trimIndent(),
    ) { cursor -> "con|${cursor.getString(0)}|${cursor.getString(1)}|${normalize(cursor.getString(2), schema)}" }
    return lines.sorted()
}

/** Lines only in [expected] ("missing") and only in [actual] ("unexpected"), at most [limit] of them. */
internal fun describeCatalogDifferences(expected: List<String>, actual: List<String>, limit: Int = 40): List<String> {
    val missing = (expected - actual.toSet()).map { "missing: $it" }
    val unexpected = (actual - expected.toSet()).map { "unexpected: $it" }
    val all = missing + unexpected
    return if (all.size <= limit) all else all.take(limit) + "... and ${all.size - limit} more"
}

private fun queryLines(driver: SqlDriver, schema: String, sql: String, line: (SqlCursor) -> String): List<String> =
    driver.executeQuery(
        null, sql, parameters = 1, binders = { bindString(0, schema) },
        mapper = { cursor: SqlCursor ->
            val rows = mutableListOf<String>()
            while (cursor.next().value) rows += line(cursor)
            QueryResult.Value(rows)
        },
    ).value

private fun normalize(raw: String?, schema: String): String {
    if (raw.isNullOrBlank()) return ""
    return raw
        .replace("\"$schema\".", "")
        .replace("$schema.", "")
        .replace(Regex("::[a-zA-Z_][a-zA-Z0-9_]*"), "")
        .replace("'", "")
        .replace("\"", "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()
}
