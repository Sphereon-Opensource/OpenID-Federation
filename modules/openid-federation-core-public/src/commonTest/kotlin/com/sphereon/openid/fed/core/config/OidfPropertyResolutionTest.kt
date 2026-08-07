package com.sphereon.openid.fed.core.config

import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OidfPropertyResolutionTest {

    @BeforeTest
    fun setUp() {
        OidfConfigBootstrap.resetForTests()
        DefaultAppMapPropertySource.getSource().keys
            .filter { it.startsWith("oidf.test.") }
            .forEach { DefaultAppMapPropertySource.deleteProperty(it) }
    }

    @AfterTest
    fun tearDown() {
        OidfConfigBootstrap.resetForTests()
        DefaultAppMapPropertySource.getSource().keys
            .filter { it.startsWith("oidf.test.") }
            .forEach { DefaultAppMapPropertySource.deleteProperty(it) }
    }

    @Test
    fun resolve_prefers_app_map_over_env() {
        val key = "oidf.test.resolution.priority"
        DefaultAppMapPropertySource.addProperty(key, "from-map")
        OidfConfigSources.installEnvironment { if (it == key) "from-env" else null }

        val value = OidfPropertyResolution.resolveString(key = key, default = "from-default")
        assertEquals("from-map", value)
    }

    @Test
    fun resolve_env_beats_file_and_hardcoded_defaults() {
        val key = OidfConfigKeys.Identity.MODE
        OidfFilePropertySource.putAll(mapOf(key to "platform"))
        OidfConfigSources.installEnvironment { if (it == key) "legacy" else null }

        val value = OidfPropertyResolution.resolveString(key = key, default = "should-not-use")
        assertEquals("legacy", value)
    }

    @Test
    fun resolve_file_beats_hardcoded_defaults() {
        val key = "oidf.test.file.only"
        OidfFilePropertySource.putAll(mapOf(key to "from-file"))
        OidfConfigSources.installEnvironment { null }

        val value = OidfPropertyResolution.resolveString(key = key, default = "from-default")
        assertEquals("from-file", value)
    }

    @Test
    fun resolve_uses_hardcoded_defaults_for_known_keys() {
        OidfConfigSources.installEnvironment { null }
        val value = OidfPropertyResolution.resolveString(
            key = OidfConfigKeys.Identity.SESSION_FIXED_TENANT_ID,
            default = "should-not-use",
        )
        assertEquals("default", value)
    }

    @Test
    fun resolveStringOrNull_returns_null_for_empty() {
        OidfConfigSources.installEnvironment { null }
        assertNull(
            OidfPropertyResolution.resolveStringOrNull(key = "oidf.test.missing.completely"),
        )
    }

    @Test
    fun sensitive_key_detection() {
        assertTrue(OidfPropertyResolution.isSensitiveKey("oidf.datasource.password"))
        assertTrue(OidfPropertyResolution.isSensitiveKey("kms.providers.azure.credentialopts.clientsecret"))
        assertFalse(OidfPropertyResolution.isSensitiveKey("oidf.federation.root.identifier"))
    }

    @Test
    fun parse_properties_text() {
        val text = """
            # comment
            oidf.a=1
            oidf.b = two
        """.trimIndent()
        val map = OidfConfigFileLoader.parsePropertiesText(text)
        assertEquals("1", map["oidf.a"])
        assertEquals("two", map["oidf.b"])
    }
}
