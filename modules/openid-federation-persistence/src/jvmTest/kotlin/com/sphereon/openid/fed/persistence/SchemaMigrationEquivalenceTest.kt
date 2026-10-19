package com.sphereon.openid.fed.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.core.api.conf.Env
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
import kotlin.test.assertFailsWith
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

    private val schemaRunId = java.util.UUID.randomUUID().toString().replace("-", "")
    private val greenfieldSchema = "oidf_mig_greenfield_$schemaRunId"
    private val upgradeSchema = "oidf_mig_upgrade_$schemaRunId"

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
            Database.Schema.create(driver)
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
            Database.Schema.create(driver)
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

    @Test
    fun adminDocumentUpgradeRetainsPublishedBytesAndKeyRoutes() {
        withSchema(upgradeSchema) { driver, conn ->
            Database.Schema.migrate(driver, 0L, 27L)
            conn.createStatement().use { st ->
                st.execute("INSERT INTO Account(id, username, identifier) VALUES ('10000000-0000-0000-0000-000000000001', 'admin-upgrade', 'https://ta.example')")
                st.execute("INSERT INTO Jwk(id, account_id, alg, kid, kms, kms_key_ref, key) VALUES ('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','ES256','original-kid','azure','original-key-reference','{\"kty\":\"EC\"}')")
                st.execute("INSERT INTO EntityConfigurationStatement(account_id, statement, expires_at) VALUES ('10000000-0000-0000-0000-000000000001','exact.original.jwt.bytes',9999999999)")
                st.execute("INSERT INTO TrustMarkType(id, account_id, identifier, definition, version) VALUES ('40000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','https://ta.example/marks/retained','{\"name\":\"Retained\",\"lifetimeSeconds\":900}',2)")
                st.execute("INSERT INTO TrustMarkTypeRevision(trust_mark_type_id, version, definition) VALUES ('40000000-0000-0000-0000-000000000001',1,NULL),('40000000-0000-0000-0000-000000000001',2,'{\"name\":\"Retained\",\"lifetimeSeconds\":900}')")
                st.execute("INSERT INTO Subordinate(id, account_id, identifier) VALUES ('30000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','https://op.example')")
                st.execute("INSERT INTO SubordinateStatement(subordinate_id, iss, sub, statement, expires_at) VALUES ('30000000-0000-0000-0000-000000000001','https://ta.example','https://op.example','exact.subordinate.jwt.bytes',9999999999)")
            }
            Database.Schema.migrate(driver, 27L, Database.Schema.version)
            conn.createStatement().use { st ->
                st.executeQuery("SELECT statement FROM EntityConfigurationStatement WHERE account_id='10000000-0000-0000-0000-000000000001'").use { rs ->
                    assertTrue(rs.next()); assertEquals("exact.original.jwt.bytes", rs.getString(1))
                }
                st.executeQuery("SELECT statement FROM SubordinateStatement WHERE subordinate_id='30000000-0000-0000-0000-000000000001'").use { rs ->
                    assertTrue(rs.next()); assertEquals("exact.subordinate.jwt.bytes", rs.getString(1))
                }
                st.executeQuery("SELECT kid, kms, kms_key_ref FROM Jwk WHERE id='20000000-0000-0000-0000-000000000001'").use { rs ->
                    assertTrue(rs.next()); assertEquals("original-kid", rs.getString(1)); assertEquals("azure", rs.getString(2)); assertEquals("original-key-reference", rs.getString(3))
                }
                st.executeQuery("SELECT version, definition FROM TrustMarkType WHERE id='40000000-0000-0000-0000-000000000001'").use { rs ->
                    assertTrue(rs.next()); assertEquals(2L, rs.getLong(1)); assertEquals("{\"name\":\"Retained\",\"lifetimeSeconds\":900}", rs.getString(2))
                }
                st.executeQuery("SELECT COUNT(*) FROM TrustMarkTypeRevision WHERE trust_mark_type_id='40000000-0000-0000-0000-000000000001'").use { rs ->
                    assertTrue(rs.next()); assertEquals(2L, rs.getLong(1))
                }
                st.executeQuery("SELECT COUNT(*) FROM AdminDocument").use { rs -> assertTrue(rs.next()); assertEquals(0L, rs.getLong(1)) }
            }
        }
    }

    @Test
    fun logicalKeyRolloverUpgradePreservesPhysicalRoutesPublicationsAndSelection() {
        val account = "10000000-0000-0000-0000-000000000001"
        val original = "20000000-0000-0000-0000-000000000001"
        val replacement = "20000000-0000-0000-0000-000000000002"
        val publicKey = "{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"alg\":\"EdDSA\",\"kid\":\"original-kid\"}"
        withSchema(upgradeSchema) { driver, conn ->
            Database.Schema.migrate(driver, 0L, 28L)
            conn.createStatement().use { st ->
                st.execute("INSERT INTO Account(id, username, identifier) VALUES ('$account','logical-key-upgrade','https://ta.example')")
                st.execute("INSERT INTO Jwk(id, account_id, alg, kid, kms, kms_key_ref, key) VALUES ('$original','$account','EdDSA','original-kid','krh-retained','retained-physical-reference','$publicKey')")
                st.execute("INSERT INTO AccountSigningKey(account_id,jwk_id,revision) VALUES ('$account','$original',7)")
                st.execute("INSERT INTO AdminDocument(account_id,collection,resource_id,document,etag,updated_at,artifact) VALUES ('$account','configuration','','{}','retained-revision',123,'exact.retained.jwt.bytes')")
                st.execute("INSERT INTO AdminDocument(account_id,collection,resource_id,document,etag,updated_at) VALUES ('$account','key-preparation','$original','{\"status\":\"COMMITTED\",\"generated\":true}','retained-private-revision',123)")
                val insertReplacement = "INSERT INTO Jwk(id, account_id, alg, kid, kms, kms_key_ref, key) VALUES ('$replacement','$account','EdDSA','replacement-kid','krh-retained','retained-physical-reference','$publicKey')"
                val beforeUpgrade = assertFailsWith<java.sql.SQLException> { st.execute(insertReplacement) }
                assertEquals("23505", beforeUpgrade.sqlState, "The populated old schema enforces physical-reference uniqueness")
                Database.Schema.migrate(driver, 28L, Database.Schema.version)
                st.execute(insertReplacement)
                st.executeQuery("SELECT id,kms,kms_key_ref,key FROM Jwk WHERE account_id='$account' ORDER BY id").use { rows ->
                    for (expectedId in listOf(original, replacement)) {
                        assertTrue(rows.next()); assertEquals(expectedId, rows.getString(1))
                        assertEquals("krh-retained", rows.getString(2)); assertEquals("retained-physical-reference", rows.getString(3))
                        assertEquals(publicKey, rows.getString(4))
                    }
                    assertTrue(!rows.next())
                }
                st.executeQuery("SELECT jwk_id,revision FROM AccountSigningKey WHERE account_id='$account'").use { rows ->
                    assertTrue(rows.next()); assertEquals(original, rows.getString(1)); assertEquals(7L, rows.getLong(2))
                }
                st.executeQuery("SELECT etag,artifact FROM AdminDocument WHERE account_id='$account' AND collection='configuration'").use { rows ->
                    assertTrue(rows.next()); assertEquals("retained-revision", rows.getString(1)); assertEquals("exact.retained.jwt.bytes", rows.getString(2))
                }
                st.executeQuery("SELECT document,etag FROM AdminDocument WHERE account_id='$account' AND collection='key-preparation'").use { rows ->
                    assertTrue(rows.next()); assertEquals("{\"status\":\"COMMITTED\",\"generated\":true}", rows.getString(1)); assertEquals("retained-private-revision", rows.getString(2))
                }
                val duplicateKid = assertFailsWith<java.sql.SQLException> {
                    st.execute("INSERT INTO Jwk(account_id,kid,kms,kms_key_ref,key) VALUES ('$account','replacement-kid','other-provider','other-reference','{}')")
                }
                assertEquals("23505", duplicateKid.sqlState, "Logical kid uniqueness remains enforced")
                st.executeQuery("SELECT indexdef FROM pg_indexes WHERE schemaname='$upgradeSchema' AND indexname='jwk_kms_key_ref_account_id_active'").use { rows ->
                    assertTrue(rows.next()); assertTrue(!rows.getString(1).contains("UNIQUE")); assertTrue(!rows.next())
                }
            }
        }
        withSchema(greenfieldSchema) { driver, _ -> Database.Schema.migrate(driver, 0L, Database.Schema.version) }
        assertEquals(fingerprint(greenfieldSchema), fingerprint(upgradeSchema), "Fresh and populated upgraded schema catalogs must agree")
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
            Env.get(k)?.takeIf { it.isNotBlank() }?.let { return it }
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
