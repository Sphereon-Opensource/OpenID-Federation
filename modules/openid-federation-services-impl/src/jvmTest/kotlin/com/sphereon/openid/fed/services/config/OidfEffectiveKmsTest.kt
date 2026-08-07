package com.sphereon.openid.fed.services.config

import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.core.api.conf.ConfigService
import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.openid.fed.common.config.OidfEnvOverrides
import com.sphereon.openid.fed.core.config.OidfConfigBootstrap
import com.sphereon.openid.fed.core.config.OidfConfigKeys
import com.sphereon.openid.fed.core.config.OidfConfigSources
import com.sphereon.openid.fed.core.config.OidfFilePropertySource
import io.mockk.every
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OidfEffectiveKmsTest {

    private lateinit var binder: OidfConfigBinderImpl

    @BeforeTest
    fun setUp() {
        OidfEnvOverrides.clear()
        OidfConfigBootstrap.resetForTests()
        OidfFilePropertySource.clear()
        DefaultAppMapPropertySource.getSource().keys
            .filter { it.startsWith("oidf.") }
            .toList()
            .forEach { DefaultAppMapPropertySource.deleteProperty(it) }
        OidfConfigSources.installEnvironment { null }
        val appConfig = mockk<AppConfigService>(relaxed = true)
        every { appConfig.getPropertyAsString(any(), any()) } returns null
        binder = OidfConfigBinderImpl(appConfig)
        OidfFilePropertySource.putAll(
            mapOf(
                OidfConfigKeys.Kms.DEFAULT_PROVIDER to "memory",
                OidfConfigKeys.Tenant.kmsProvider("t1") to "azure",
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        OidfEnvOverrides.clear()
        OidfConfigBootstrap.resetForTests()
        OidfFilePropertySource.clear()
    }

    @Test
    fun explicit_provider_wins() {
        assertEquals(
            "aws",
            binder.resolveEffectiveKmsProviderId("t1", explicitProviderId = "aws"),
        )
    }

    @Test
    fun tenant_catalog_wins_over_app() {
        assertEquals("azure", binder.resolveEffectiveKmsProviderId("t1"))
        assertEquals("memory", binder.resolveEffectiveKmsProviderId(null))
        assertEquals("memory", binder.resolveEffectiveKmsProviderId("unknown"))
    }

    @Test
    fun session_tenant_config_wins_over_catalog() {
        val session = mockk<ConfigService>(relaxed = true)
        every { session.getPropertyAsString(OidfConfigKeys.Kms.DEFAULT_PROVIDER, null) } returns "aws"
        every { session.getPropertyAsString(any(), any()) } answers {
            if (firstArg<String>() == OidfConfigKeys.Kms.DEFAULT_PROVIDER) "aws" else null
        }
        assertEquals(
            "aws",
            binder.resolveEffectiveKmsProviderId("t1", sessionTenantConfig = session),
        )
    }
}
