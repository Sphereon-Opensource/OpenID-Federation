package com.sphereon.openid.fed.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.openid.fed.core.tenant.IdentityInstallState
import com.sphereon.openid.fed.core.tenant.IdentityMode
import com.sphereon.openid.fed.core.tenant.IdentityModeDefaults
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves SQLDelight upgrade path produces the same catalog as a greenfield install.
 *
 * ## Scenario
 * 1. **Greenfield**: migrate `0 → Database.Schema.version` on empty schema.
 * 2. **Upgrade**: migrate `0 → BASELINE` (pre-0.25-ish), seed Account rows, then
 *    `BASELINE → Database.Schema.version` (as an existing install would).
 * 3. Compare [SchemaCatalogFingerprint] of both schemas (tables/columns/indexes/FKs).
 * 4. Assert seeded data survives and [IdentityInstallState] marks upgrade → ACCOUNT default.
 *
 * Requires PostgreSQL (same as product). Skips if DB is unreachable.
 *
 * Env (same as product):
 * - `OIDF_DATASOURCE_URL` / `DATASOURCE_URL` (default `jdbc:postgresql://localhost:5432/openid-federation-db`)
 * - `OIDF_DATASOURCE_USER` / `DATASOURCE_USER` (default `postgres`)
 * - `OIDF_DATASOURCE_PASSWORD` / `DATASOURCE_PASSWORD` (default `postgres`)
 */
class SchemaMigrationEquivalenceTest {

    /**
     * Schema version after migrations 1.sqm..17.sqm (includes tenant_source; before 18 TA hints, 19 constraints).
     * Represents a plausible develop-era install prior to TrustAnchorHint / SubordinateConstraint.
     */
    private val baselineVersion: Long = 17L

    private val greenfieldSchema = "oidf_mig_greenfield"
    private val upgradeSchema = "oidf_mig_upgrade"

    private lateinit var adminUrl: String
    private lateinit var user: String
    private lateinit var password: String

    @Before
    fun setUp() {
        IdentityInstallState.resetForTests()
        adminUrl = env("OIDF_DATASOURCE_URL", "DATASOURCE_URL")
            ?: "jdbc:postgresql://localhost:5432/openid-federation-db"
        user = env("OIDF_DATASOURCE_USER", "DATASOURCE_USER") ?: "postgres"
        password = env("OIDF_DATASOURCE_PASSWORD", "DATASOURCE_PASSWORD") ?: "postgres"

        Assume.assumeTrue("PostgreSQL not reachable at $adminUrl", canConnect(adminUrl, user, password))

        adminConnection().use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto")
                st.execute("DROP SCHEMA IF EXISTS $greenfieldSchema CASCADE")
                st.execute("DROP SCHEMA IF EXISTS $upgradeSchema CASCADE")
                st.execute("CREATE SCHEMA $greenfieldSchema")
                st.execute("CREATE SCHEMA $upgradeSchema")
            }
        }
    }

    @After
    fun tearDown() {
        IdentityInstallState.resetForTests()
        if (!::adminUrl.isInitialized) return
        if (!canConnect(adminUrl, user, password)) return
        try {
            adminConnection().use { conn ->
                conn.createStatement().use { st ->
                    st.execute("DROP SCHEMA IF EXISTS $greenfieldSchema CASCADE")
                    st.execute("DROP SCHEMA IF EXISTS $upgradeSchema CASCADE")
                }
            }
        } catch (_: Exception) {
            // ignore cleanup failures
        }
    }

    @Test
    fun upgradeFromBaseline_matches_greenfield_schema_and_preserves_data() {
        val latest = Database.Schema.version
        assertTrue(baselineVersion in 1 until latest, "baselineVersion=$baselineVersion must be < Schema.version=$latest")

        // --- Greenfield: empty → latest ---
        withSchema(greenfieldSchema) { driver, conn ->
            Database.Schema.migrate(driver, 0L, latest)
            recordSchemaVersion(conn, latest, 0L)
        }

        // --- Upgrade: empty → baseline, seed, then baseline → latest ---
        withSchema(upgradeSchema) { driver, conn ->
            Database.Schema.migrate(driver, 0L, baselineVersion)
            recordSchemaVersion(conn, baselineVersion, 0L)

            // Seed data as a develop-era install would have
            conn.createStatement().use { st ->
                st.execute(
                    """
                    INSERT INTO Account (username, identifier)
                    VALUES ('upgrade_leaf', 'https://example.com/upgrade_leaf')
                    """.trimIndent(),
                )
            }

            // Simulate process detecting existing DB before continuing migrate
            IdentityInstallState.markExistingDatabase(baselineVersion)
            assertTrue(IdentityInstallState.isExistingDatabase)
            assertEquals(
                IdentityMode.ACCOUNT,
                IdentityModeDefaults.resolve(
                    explicitMode = null,
                    accountModulesPresent = false,
                    isExistingDatabase = true,
                ),
            )

            Database.Schema.migrate(driver, baselineVersion, latest)
            recordSchemaVersion(conn, latest, baselineVersion)
        }

        // --- Compare catalogs ---
        val greenfieldFp = fingerprint(greenfieldSchema)
        val upgradeFp = fingerprint(upgradeSchema)

        if (greenfieldFp != upgradeFp) {
            val diff = unifiedDiff(greenfieldFp, upgradeFp)
            throw AssertionError(
                "Migrated schema does not match greenfield install.\n" +
                    "baselineVersion=$baselineVersion → $latest vs greenfield 0 → $latest\n" +
                    diff,
            )
        }

        // --- Data survival + post-upgrade columns ---
        schemaConnection(upgradeSchema).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """SELECT username, identifier, tenant_source FROM Account
                       WHERE username = 'upgrade_leaf'""",
                ).use { rs ->
                    assertTrue(rs.next(), "upgrade_leaf account missing after migration")
                    assertEquals("upgrade_leaf", rs.getString("username"))
                    assertEquals("https://example.com/upgrade_leaf", rs.getString("identifier"))
                    // 17.sqm adds tenant_source with default 'account'
                    val source = rs.getString("tenant_source")
                    assertTrue(
                        source == null || source == "account",
                        "unexpected tenant_source=$source",
                    )
                }

                // Tables introduced after baseline must exist (PG folds unquoted names to lowercase)
                st.executeQuery(
                    """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = '$upgradeSchema'
                      AND lower(table_name) IN ('trustanchorhint', 'subordinateconstraint')
                    """.trimIndent(),
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals(2, rs.getInt(1), "post-baseline tables missing on upgrade path")
                }
            }
        }
    }

    @Test
    fun greenfield_and_upgrade_both_reach_schema_version() {
        val latest = Database.Schema.version
        withSchema(greenfieldSchema) { driver, conn ->
            Database.Schema.migrate(driver, 0L, latest)
            recordSchemaVersion(conn, latest, 0L)
            assertEquals(latest, readSchemaVersion(conn))
        }
        withSchema(upgradeSchema) { driver, conn ->
            Database.Schema.migrate(driver, 0L, baselineVersion)
            recordSchemaVersion(conn, baselineVersion, 0L)
            Database.Schema.migrate(driver, baselineVersion, latest)
            recordSchemaVersion(conn, latest, baselineVersion)
            assertEquals(latest, readSchemaVersion(conn))
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun withSchema(schema: String, block: (SqlDriver, Connection) -> Unit) {
        val ds = dataSourceForSchema(schema)
        try {
            val driver = ds.asJdbcDriver()
            ds.connection.use { conn ->
                conn.createStatement().use { it.execute("SET search_path TO $schema, public") }
                // SQLDelight tables land in search_path first schema
                block(driver, conn)
            }
        } finally {
            ds.close()
        }
    }

    private fun dataSourceForSchema(schema: String): HikariDataSource {
        val config = HikariConfig()
        config.jdbcUrl = adminUrl
        config.username = user
        config.password = password
        config.maximumPoolSize = 2
        config.poolName = "mig-test-$schema"
        // Ensure new connections use the right schema for unqualified DDL from SQLDelight
        config.connectionInitSql = "SET search_path TO $schema, public"
        return HikariDataSource(config)
    }

    private fun fingerprint(schema: String): String =
        schemaConnection(schema).use { SchemaCatalogFingerprint.capture(it, schema) }

    private fun schemaConnection(schema: String): Connection {
        val conn = DriverManager.getConnection(adminUrl, user, password)
        conn.createStatement().use { it.execute("SET search_path TO $schema, public") }
        return conn
    }

    private fun adminConnection(): Connection =
        DriverManager.getConnection(adminUrl, user, password)

    private fun recordSchemaVersion(conn: Connection, version: Long, from: Long) {
        conn.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER NOT NULL,
                    migrated_from INTEGER,
                    migrated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    description TEXT
                )
                """.trimIndent(),
            )
            st.execute(
                "INSERT INTO schema_version (version, migrated_from, description) " +
                    "VALUES ($version, $from, 'test migrate $from -> $version')",
            )
        }
    }

    private fun readSchemaVersion(conn: Connection): Long {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT version FROM schema_version ORDER BY version DESC LIMIT 1").use { rs ->
                assertTrue(rs.next())
                return rs.getLong(1)
            }
        }
    }

    private fun canConnect(url: String, user: String, password: String): Boolean =
        try {
            DriverManager.getConnection(url, user, password).use { true }
        } catch (_: Exception) {
            false
        }

    private fun env(vararg keys: String): String? {
        for (k in keys) {
            System.getenv(k)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun unifiedDiff(expected: String, actual: String): String {
        val exp = expected.lines().toSet()
        val act = actual.lines().toSet()
        val onlyExp = (exp - act).sorted()
        val onlyAct = (act - exp).sorted()
        return buildString {
            appendLine("--- greenfield only (${onlyExp.size})")
            onlyExp.forEach { appendLine("- $it") }
            appendLine("+++ upgrade only (${onlyAct.size})")
            onlyAct.forEach { appendLine("+ $it") }
        }
    }
}
