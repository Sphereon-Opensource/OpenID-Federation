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
 * The shape of one PostgreSQL schema as sorted, comparable lines. Compared, exactly as PostgreSQL reports them:
 * - columns: position, type, nullability, default, character length, numeric and datetime precision and scale,
 *   collation, identity and generated-column settings (and the generation expression);
 * - indexes (their full definitions);
 * - constraints: primary key, unique, foreign key and check (their full definitions);
 * - non-internal triggers (their full definitions);
 * - row-level security: whether it is enabled or forced per table, and every policy;
 * - sequences not owned by a column, and standalone enum, domain, range and composite types.
 *
 * The only normalization is removing the schema qualifier, so two schemas with the same shape give the same lines.
 * Letter case, quotes and casts are kept. Row data is not part of it.
 */
internal fun captureSchemaCatalog(driver: SqlDriver, schema: String): List<String> {
    val excluded = BOOKKEEPING_TABLES.joinToString { "'$it'" }
    val lines = mutableListOf<String>()
    lines += queryLines(
        driver, schema,
        """
        SELECT table_name, column_name, ordinal_position::text, udt_name, is_nullable, column_default,
               character_maximum_length::text, numeric_precision::text, numeric_scale::text, datetime_precision::text,
               collation_name, is_identity, identity_generation, is_generated, generation_expression
        FROM information_schema.columns
        WHERE table_schema = ? AND table_name NOT IN ($excluded)
        """.trimIndent(),
    ) { cursor -> "col|" + (0..14).joinToString("|") { index -> normalize(cursor.getString(index), schema) } }
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
    lines += queryLines(
        driver, schema,
        """
        SELECT rel.relname, trg.tgname, pg_get_triggerdef(trg.oid, true)
        FROM pg_catalog.pg_trigger trg
        JOIN pg_catalog.pg_class rel ON rel.oid = trg.tgrelid
        JOIN pg_catalog.pg_namespace ns ON ns.oid = rel.relnamespace
        WHERE ns.nspname = ? AND NOT trg.tgisinternal AND rel.relname NOT IN ($excluded)
        """.trimIndent(),
    ) { cursor -> "trg|${cursor.getString(0)}|${cursor.getString(1)}|${normalize(cursor.getString(2), schema)}" }
    lines += queryLines(
        driver, schema,
        """
        SELECT rel.relname, rel.relrowsecurity::text, rel.relforcerowsecurity::text
        FROM pg_catalog.pg_class rel JOIN pg_catalog.pg_namespace ns ON ns.oid = rel.relnamespace
        WHERE ns.nspname = ? AND rel.relkind IN ('r', 'p') AND rel.relname NOT IN ($excluded)
        """.trimIndent(),
    ) { cursor -> "rls|${cursor.getString(0)}|${cursor.getString(1)}|${cursor.getString(2)}" }
    lines += queryLines(
        driver, schema,
        """
        SELECT tablename, policyname, permissive, roles::text, cmd, qual, with_check
        FROM pg_catalog.pg_policies WHERE schemaname = ? AND tablename NOT IN ($excluded)
        """.trimIndent(),
    ) { cursor -> "pol|" + (0..6).joinToString("|") { index -> normalize(cursor.getString(index), schema) } }
    lines += queryLines(
        driver, schema,
        """
        SELECT cls.relname, format_type(seq.seqtypid, NULL), seq.seqstart::text, seq.seqincrement::text,
               seq.seqmax::text, seq.seqmin::text, seq.seqcache::text, seq.seqcycle::text
        FROM pg_catalog.pg_class cls
        JOIN pg_catalog.pg_namespace ns ON ns.oid = cls.relnamespace
        JOIN pg_catalog.pg_sequence seq ON seq.seqrelid = cls.oid
        WHERE ns.nspname = ? AND cls.relkind = 'S'
          AND NOT EXISTS (
              SELECT 1 FROM pg_catalog.pg_depend dep
              WHERE dep.objid = cls.oid AND dep.deptype IN ('a', 'i') AND dep.refobjsubid > 0
          )
        """.trimIndent(),
    ) { cursor -> "seq|" + (0..7).joinToString("|") { index -> normalize(cursor.getString(index), schema) } }
    lines += queryLines(
        driver, schema,
        """
        SELECT typ.typname, typ.typtype::text,
               coalesce((SELECT string_agg(label.enumlabel, ',' ORDER BY label.enumsortorder)
                         FROM pg_catalog.pg_enum label WHERE label.enumtypid = typ.oid), '')
        FROM pg_catalog.pg_type typ
        JOIN pg_catalog.pg_namespace ns ON ns.oid = typ.typnamespace
        WHERE ns.nspname = ?
          AND (typ.typtype IN ('e', 'd', 'r', 'm')
               OR (typ.typtype = 'c' AND EXISTS (
                   SELECT 1 FROM pg_catalog.pg_class own WHERE own.oid = typ.typrelid AND own.relkind = 'c')))
        """.trimIndent(),
    ) { cursor -> "typ|${cursor.getString(0)}|${cursor.getString(1)}|${cursor.getString(2)}" }
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

/** Removes the schema qualifier and nothing else. */
private fun normalize(raw: String?, schema: String): String {
    if (raw.isNullOrEmpty()) return ""
    return raw.replace("\"$schema\".", "").replace("$schema.", "")
}
