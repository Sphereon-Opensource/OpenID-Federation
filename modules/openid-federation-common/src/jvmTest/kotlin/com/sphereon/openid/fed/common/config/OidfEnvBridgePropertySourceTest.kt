package com.sphereon.openid.fed.common.config

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
}
