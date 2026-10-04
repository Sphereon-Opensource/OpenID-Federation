package com.sphereon.openid.fed.common.config

import com.sphereon.core.api.conf.Env
import com.sphereon.openid.fed.core.config.OidfConfigKeys
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ensures the IDK [OidfEnvBridgePropertySource] surfaces legacy + normalized env under oidf.* keys.
 */
class OidfEnvBridgePropertySourceTest {

    private val source = OidfEnvBridgePropertySource()

    @BeforeTest
    fun setUp() {
        OidfEnvOverrides.clear()
    }

    @AfterTest
    fun tearDown() {
        OidfEnvOverrides.clear()
    }

    @Test
    fun legacy_root_identifier_resolves_as_oidf_key() {
        OidfEnvOverrides.withEnv(mapOf("ROOT_IDENTIFIER" to "https://legacy.example")) {
            assertEquals(
                "https://legacy.example",
                source.getPropertyAsString(OidfConfigKeys.Federation.ROOT_IDENTIFIER),
            )
            assertTrue(source.hasProperty(OidfConfigKeys.Federation.ROOT_IDENTIFIER))
            assertTrue(source.getAllPropertyNames().isNotEmpty())
        }
    }

    @Test
    fun oauth2_legacy_issuer_alias_resolves() {
        OidfEnvOverrides.withEnv(
            mapOf("OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI" to "https://kc.example/realms/x"),
        ) {
            assertEquals(
                "https://kc.example/realms/x",
                source.getPropertyAsString(OidfConfigKeys.OAuth2.ISSUER_URI),
            )
        }
    }

    @Test
    fun normalized_oidf_env_also_resolves() {
        OidfEnvOverrides.withEnv(
            mapOf("OIDF_DATASOURCE_URL" to "jdbc:postgresql://db/oidf"),
        ) {
            assertEquals(
                "jdbc:postgresql://db/oidf",
                source.getPropertyAsString(OidfConfigKeys.Datasource.URL),
            )
        }
    }

    @Test
    fun empty_env_has_no_properties() {
        OidfEnvOverrides.withEnv(emptyMap()) {
            assertFalse(source.hasProperty(OidfConfigKeys.Federation.ROOT_IDENTIFIER))
            assertNull(source.getPropertyAsString(OidfConfigKeys.Federation.ROOT_IDENTIFIER))
            assertTrue(source.getSource().isEmpty())
        }
    }

    @Test
    fun typed_int_coercion() {
        OidfEnvOverrides.withEnv(mapOf("ADMIN_SERVER_PORT" to "9099")) {
            assertEquals(9099, source.getProperty(OidfConfigKeys.Server.Admin.PORT, Int::class))
        }
    }

    @Test
    fun source_name_and_provider_id_are_stable() {
        assertEquals(OidfEnvBridgePropertySource.NAME, source.getName())
        assertEquals("oidf-legacy-env", OidfEnvBridgePropertySource.PROVIDER_ID)
    }

    @Test
    fun repeated_refresh_without_environment_change_keeps_content_revision() {
        OidfEnvOverrides.withEnv(mapOf("ROOT_IDENTIFIER" to "https://stable.example")) {
            source.refreshIfNeeded()
            val stableRevision = source.contentRevision
            repeat(3) {
                assertEquals("https://stable.example", source.getPropertyAsString(OidfConfigKeys.Federation.ROOT_IDENTIFIER))
                source.refreshIfNeeded()
                assertEquals(stableRevision, source.contentRevision)
            }
        }
    }

    @Test
    fun legacy_override_add_change_and_remove_advance_revision_then_stabilize() {
        OidfEnvOverrides.withEnv(emptyMap()) {
            source.refreshIfNeeded()
            val emptyRevision = source.contentRevision

            OidfEnvOverrides.map = mapOf("ROOT_IDENTIFIER" to "https://first.example")
            source.refreshIfNeeded()
            val addedRevision = source.contentRevision
            assertTrue(addedRevision > emptyRevision)
            assertEquals("https://first.example", source.getPropertyAsString(OidfConfigKeys.Federation.ROOT_IDENTIFIER))
            source.refreshIfNeeded()
            assertEquals(addedRevision, source.contentRevision)

            OidfEnvOverrides.map = mapOf("ROOT_IDENTIFIER" to "https://second.example")
            source.refreshIfNeeded()
            val changedRevision = source.contentRevision
            assertTrue(changedRevision > addedRevision)
            assertEquals("https://second.example", source.getPropertyAsString(OidfConfigKeys.Federation.ROOT_IDENTIFIER))
            source.refreshIfNeeded()
            assertEquals(changedRevision, source.contentRevision)

            OidfEnvOverrides.map = emptyMap()
            source.refreshIfNeeded()
            val removedRevision = source.contentRevision
            assertTrue(removedRevision > changedRevision)
            assertNull(source.getPropertyAsString(OidfConfigKeys.Federation.ROOT_IDENTIFIER))
            source.refreshIfNeeded()
            assertEquals(removedRevision, source.contentRevision)
        }
    }

    @Test
    fun unmapped_normalized_oidf_key_changes_advance_revision_then_stabilize() {
        val propertyKey = "oidf.experimental.observation.flag"
        OidfEnvOverrides.withEnv(mapOf("OIDF_EXPERIMENTAL_OBSERVATION_FLAG" to "first")) {
            assertEquals("first", source.getPropertyAsString(propertyKey))
            source.refreshIfNeeded()
            val firstRevision = source.contentRevision
            source.refreshIfNeeded()
            assertEquals(firstRevision, source.contentRevision)

            OidfEnvOverrides.map = mapOf("OIDF_EXPERIMENTAL_OBSERVATION_FLAG" to "second")
            source.refreshIfNeeded()
            val changedRevision = source.contentRevision
            assertTrue(changedRevision > firstRevision)
            assertEquals("second", source.getPropertyAsString(propertyKey))
            source.refreshIfNeeded()
            assertEquals(changedRevision, source.contentRevision)

            OidfEnvOverrides.map = emptyMap()
            source.refreshIfNeeded()
            val removedRevision = source.contentRevision
            assertTrue(removedRevision > changedRevision)
            assertNull(source.getPropertyAsString(propertyKey))
            source.refreshIfNeeded()
            assertEquals(removedRevision, source.contentRevision)
        }
    }

    @Test
    fun queried_jvm_system_property_fallback_changes_advance_revision_then_stabilize() {
        val propertyKey = "oidf.bridge.revision.system.property.test4873"
        val envName = normalizeKeyForEnv(propertyKey)
        assertFalse(Env.getAll().containsKey(envName), "test-owned system property must not be shadowed by an environment variable")
        val previous = System.getProperty(envName)
        try {
            System.clearProperty(envName)
            assertNull(source.getPropertyAsString(propertyKey))
            source.refreshIfNeeded()
            val absentRevision = source.contentRevision

            System.setProperty(envName, "first")
            assertEquals("first", source.getPropertyAsString(propertyKey))
            source.refreshIfNeeded()
            val addedRevision = source.contentRevision
            assertTrue(addedRevision > absentRevision)
            source.refreshIfNeeded()
            assertEquals(addedRevision, source.contentRevision)

            System.setProperty(envName, "second")
            assertEquals("second", source.getPropertyAsString(propertyKey))
            source.refreshIfNeeded()
            val changedRevision = source.contentRevision
            assertTrue(changedRevision > addedRevision)
            source.refreshIfNeeded()
            assertEquals(changedRevision, source.contentRevision)

            System.clearProperty(envName)
            assertNull(source.getPropertyAsString(propertyKey))
            source.refreshIfNeeded()
            val removedRevision = source.contentRevision
            assertTrue(removedRevision > changedRevision)
            source.refreshIfNeeded()
            assertEquals(removedRevision, source.contentRevision)
        } finally {
            if (previous == null) System.clearProperty(envName) else System.setProperty(envName, previous)
        }
    }

    @Test
    fun masked_legacy_alias_does_not_advance_revision_until_normalized_winner_changes() {
        val normalizedName = "OIDF_FEDERATION_ROOT_IDENTIFIER"
        OidfEnvOverrides.withEnv(
            mapOf(normalizedName to "https://winner.example", "ROOT_IDENTIFIER" to "https://masked-one.example"),
        ) {
            assertEquals("https://winner.example", source.getPropertyAsString(OidfConfigKeys.Federation.ROOT_IDENTIFIER))
            source.refreshIfNeeded()
            val winningRevision = source.contentRevision

            OidfEnvOverrides.map =
                mapOf(normalizedName to "https://winner.example", "ROOT_IDENTIFIER" to "https://masked-two.example")
            source.refreshIfNeeded()
            assertEquals(winningRevision, source.contentRevision)
            assertEquals("https://winner.example", source.getPropertyAsString(OidfConfigKeys.Federation.ROOT_IDENTIFIER))

            OidfEnvOverrides.map =
                mapOf(normalizedName to "https://next.example", "ROOT_IDENTIFIER" to "https://masked-two.example")
            source.refreshIfNeeded()
            val changedRevision = source.contentRevision
            assertTrue(changedRevision > winningRevision)
            assertEquals("https://next.example", source.getPropertyAsString(OidfConfigKeys.Federation.ROOT_IDENTIFIER))
            source.refreshIfNeeded()
            assertEquals(changedRevision, source.contentRevision)
        }
    }
}
