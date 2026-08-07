package com.sphereon.openid.fed.services.config

import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.core.api.conf.ConfigLevel
import com.sphereon.core.api.conf.ConfigService
import com.sphereon.core.api.conf.DefaultPropertySources
import com.sphereon.core.api.conf.InterpolationPolicyProvider
import com.sphereon.core.api.conf.MapPropertySource
import com.sphereon.core.api.conf.PropertySource
import com.sphereon.core.api.conf.PropertySourceBootstrapImpl
import com.sphereon.core.api.conf.PropertySources
import com.sphereon.openid.fed.common.config.OidfEnvBridgePropertySource
import com.sphereon.openid.fed.common.config.OidfEnvOverrides
import com.sphereon.openid.fed.core.config.OidfConfigBootstrap
import com.sphereon.openid.fed.core.config.OidfConfigKeys
import io.mockk.every
import io.mockk.mockk
import kotlinx.io.files.Path
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ensures the env-bridge contribution registers into AppConfigService via
 * [PropertySourceBootstrapImpl] / [OidfConfigBootstrap.ensurePropertySourcesRegistered].
 */
class OidfEnvBridgeBootstrapTest {

    @BeforeTest
    fun setUp() {
        OidfEnvOverrides.clear()
    }

    @AfterTest
    fun tearDown() {
        OidfEnvOverrides.clear()
    }

    @Test
    fun bootstrap_registers_bridge_and_resolves_legacy_env() {
        val sources = DefaultPropertySources()
        val appConfig = RecordingAppConfigService(sources)
        val contribution = OidfEnvBridgePropertySourceContribution()
        val bootstrap = PropertySourceBootstrapImpl(
            appConfigService = appConfig,
            contributions = setOf(contribution),
        )

        bootstrap.registerAppSources()
        assertTrue(bootstrap.registeredAppSourceCount >= 1)
        assertTrue(sources.contains(OidfEnvBridgePropertySource.NAME) || sources.any { it.getName() == OidfEnvBridgePropertySource.NAME })

        OidfEnvOverrides.withEnv(mapOf("ROOT_IDENTIFIER" to "https://from-bridge.example")) {
            // After registration, AppConfig should resolve via the bridge source
            val value = appConfig.getPropertyAsString(OidfConfigKeys.Federation.ROOT_IDENTIFIER, null)
            assertEquals("https://from-bridge.example", value)
        }
    }

    @Test
    fun ensurePropertySourcesRegistered_noop_when_graph_has_no_bootstrap() {
        assertEquals(-1, OidfConfigBootstrap.ensurePropertySourcesRegistered(Any()))
    }

    @Test
    fun ensurePropertySourcesRegistered_invokes_bootstrap_on_graph() {
        val sources = DefaultPropertySources()
        val appConfig = RecordingAppConfigService(sources)
        val bootstrap = PropertySourceBootstrapImpl(
            appConfigService = appConfig,
            contributions = setOf(OidfEnvBridgePropertySourceContribution()),
        )
        val graph = object : com.sphereon.core.api.conf.PropertySourceBootstrap.Graph {
            override val propertySourceBootstrap = bootstrap
        }
        val count = OidfConfigBootstrap.ensurePropertySourcesRegistered(graph)
        assertTrue(count >= 1)
    }

    /**
     * Minimal AppConfigService that stores property sources and resolves from them.
     */
    private class RecordingAppConfigService(
        private val sources: PropertySources,
    ) : AppConfigService {
        override val configLevel: ConfigLevel = ConfigLevel.APP
        override val parent: ConfigService? = null
        override val level: ConfigLevel = ConfigLevel.APP
        override val interpolationPolicyProvider: InterpolationPolicyProvider
            get() = mockk(relaxed = true)

        override fun addPropertySource(source: PropertySource<*>): ConfigService {
            sources.add(source)
            return this
        }

        override fun removePropertySource(source: PropertySource<*>): ConfigService {
            sources.remove(source)
            return this
        }

        override fun getActiveProfile(): String = "default"
        override fun getAppName(): String = "test"
        override fun getConfigLocation(): Path = Path(".")
        override fun getPropertySources(includeParents: Boolean): PropertySources = sources
        override fun containsProperty(key: String): Boolean = getPropertyAsString(key, null) != null

        override fun <T : Any> getProperty(
            key: String,
            targetType: KClass<T>,
            defaultValue: T?,
        ): T? {
            for (source in sources) {
                source.getProperty(key, targetType)?.let { return it }
            }
            return defaultValue
        }

        override fun getPropertyAsString(key: String, defaultValue: String?): String? {
            for (source in sources) {
                source.getPropertyAsString(key)?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return defaultValue
        }

        override fun <T : Any> getRequiredProperty(
            key: String,
            targetType: KClass<T>,
            defaultValue: T?,
        ): T = getProperty(key, targetType, defaultValue) ?: error("missing $key")

        override fun getRequiredPropertyAsString(key: String, defaultValue: String?): String =
            getPropertyAsString(key, defaultValue) ?: error("missing $key")

        override fun getAllProperties(): Map<String, Any> = emptyMap()
        override fun getAllPropertiesAsString(redact: Boolean): Map<String, String> = emptyMap()
        override fun getSubProperties(prefixes: Set<String>, stripPrefix: Boolean): Map<String, Any> =
            emptyMap()

        override fun getSubPropertiesAsString(
            prefixes: Set<String>,
            stripPrefix: Boolean,
            redact: Boolean,
        ): Map<String, String> = emptyMap()

        override fun getNamespace(): String = "test"
    }
}
