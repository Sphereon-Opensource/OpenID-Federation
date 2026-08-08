package com.sphereon.openid.fed.persistence

import java.sql.Connection

/**
 * Stable, order-independent fingerprint of a PostgreSQL schema for migration equivalence tests.
 *
 * Captures tables, columns, indexes, and foreign keys in [public]-style schemas.
 * Excludes volatile rows (data) and migration bookkeeping content.
 */
object SchemaCatalogFingerprint {

    private val EXCLUDED_TABLES = setOf(
        "schema_version",
        "schema_migration_history",
    )

    /**
     * Capture structural fingerprint (columns/indexes/FKs/checks).
     * Does **not** include the PostgreSQL schema name — callers use isolated schemas
     * (`oidf_mig_greenfield` vs `oidf_mig_upgrade`) and only care about catalog shape.
     */
    fun capture(connection: Connection, schema: String = "public"): String {
        val lines = mutableListOf<String>()
        lines += columns(connection, schema)
        lines += indexes(connection, schema)
        lines += foreignKeys(connection, schema)
        lines += checkConstraints(connection, schema)
        return lines.sorted().joinToString("\n")
    }

    private fun columns(connection: Connection, schema: String): List<String> {
        val sql = """
            SELECT table_name, column_name, data_type, udt_name,
                   is_nullable, column_default, character_maximum_length
            FROM information_schema.columns
            WHERE table_schema = ?
              AND table_name NOT IN (${EXCLUDED_TABLES.joinToString { "'$it'" }})
            ORDER BY table_name, ordinal_position
        """.trimIndent()
        return connection.prepareStatement(sql).use { ps ->
            ps.setString(1, schema)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val table = rs.getString("table_name")
                        val col = rs.getString("column_name")
                        val type = rs.getString("udt_name") ?: rs.getString("data_type")
                        val nullable = rs.getString("is_nullable")
                        val def = normalizeDefault(rs.getString("column_default"))
                        val len = rs.getObject("character_maximum_length")?.toString() ?: ""
                        add("col|$table|$col|$type|$nullable|$def|$len")
                    }
                }
            }
        }
    }

    private fun indexes(connection: Connection, schema: String): List<String> {
        val sql = """
            SELECT tablename, indexname, indexdef
            FROM pg_indexes
            WHERE schemaname = ?
              AND tablename NOT IN (${EXCLUDED_TABLES.joinToString { "'$it'" }})
            ORDER BY tablename, indexname
        """.trimIndent()
        return connection.prepareStatement(sql).use { ps ->
            ps.setString(1, schema)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val table = rs.getString("tablename")
                        val name = rs.getString("indexname")
                        val def = normalizeIndexDef(rs.getString("indexdef"), schema)
                        add("idx|$table|$name|$def")
                    }
                }
            }
        }
    }

    private fun foreignKeys(connection: Connection, schema: String): List<String> {
        val sql = """
            SELECT
              tc.table_name,
              tc.constraint_name,
              kcu.column_name,
              ccu.table_name AS foreign_table,
              ccu.column_name AS foreign_column
            FROM information_schema.table_constraints AS tc
            JOIN information_schema.key_column_usage AS kcu
              ON tc.constraint_name = kcu.constraint_name
             AND tc.table_schema = kcu.table_schema
            JOIN information_schema.constraint_column_usage AS ccu
              ON ccu.constraint_name = tc.constraint_name
             AND ccu.table_schema = tc.table_schema
            WHERE tc.constraint_type = 'FOREIGN KEY'
              AND tc.table_schema = ?
              AND tc.table_name NOT IN (${EXCLUDED_TABLES.joinToString { "'$it'" }})
            ORDER BY tc.table_name, tc.constraint_name, kcu.ordinal_position
        """.trimIndent()
        return connection.prepareStatement(sql).use { ps ->
            ps.setString(1, schema)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            "fk|${rs.getString("table_name")}|${rs.getString("constraint_name")}|" +
                                "${rs.getString("column_name")}->${rs.getString("foreign_table")}." +
                                rs.getString("foreign_column"),
                        )
                    }
                }
            }
        }
    }

    private fun checkConstraints(connection: Connection, schema: String): List<String> {
        val sql = """
            SELECT tc.table_name, tc.constraint_name, cc.check_clause
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON tc.constraint_name = cc.constraint_name
             AND tc.constraint_schema = cc.constraint_schema
            WHERE tc.table_schema = ?
              AND tc.constraint_type = 'CHECK'
              AND tc.table_name NOT IN (${EXCLUDED_TABLES.joinToString { "'$it'" }})
              AND tc.constraint_name NOT LIKE '%_not_null'
            ORDER BY tc.table_name, tc.constraint_name
        """.trimIndent()
        return connection.prepareStatement(sql).use { ps ->
            ps.setString(1, schema)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            "chk|${rs.getString("table_name")}|${rs.getString("constraint_name")}|" +
                                normalizeDefault(rs.getString("check_clause")),
                        )
                    }
                }
            }
        }
    }

    private fun normalizeDefault(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(Regex("::[a-zA-Z0-9_\\s\"]+"), "")
            .replace("'", "")
            .replace("\"", "")
            .trim()
            .lowercase()
    }

    private fun normalizeIndexDef(def: String?, schema: String): String {
        if (def.isNullOrBlank()) return ""
        return def
            // DROP schema qualification so greenfield vs upgrade catalogs compare equal
            .replace("\"$schema\".", "")
            .replace("$schema.", "")
            .replace("\"", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }
}
