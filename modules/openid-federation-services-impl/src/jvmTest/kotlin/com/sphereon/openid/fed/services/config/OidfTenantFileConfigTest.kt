package com.sphereon.openid.fed.services.config

import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.openid.fed.common.config.OidfEnvOverrides
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.config.OidfConfigBootstrap
import com.sphereon.openid.fed.core.config.OidfConfigFileLoader
import com.sphereon.openid.fed.core.config.OidfConfigKeys
import com.sphereon.openid.fed.core.config.OidfConfigSources
import com.sphereon.openid.fed.core.config.OidfFilePropertySource
import com.sphereon.openid.fed.core.tenant.IdentityMode
import io.mockk.every
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tenant-overridable settings from flat `oidf.tenant.<id>.*` keys (APP catalog / file tier).
 * Nested YAML for the same keys is loaded by **IDK lib-conf-yaml**, not OIDFed.
 */
class OidfTenantFileConfigTest {

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
    }

    @AfterTest
    fun tearDown() {
        OidfEnvOverrides.clear()
        OidfConfigBootstrap.resetForTests()
        OidfFilePropertySource.clear()
    }

    @Test
    fun tenant_overrides_from_flat_keys_bind_all_overridable_fields() {
        OidfFilePropertySource.putAll(
            mapOf(
                OidfConfigKeys.Federation.ROOT_IDENTIFIER to "https://default.example",
                OidfConfigKeys.Kms.DEFAULT_PROVIDER to "memory",
                OidfConfigKeys.Identity.MODE to "account",
                OidfConfigKeys.Identity.ACCOUNT_HEADER_PRINCIPAL_CLAIM to "sub",
                OidfConfigKeys.Identity.ACCOUNT_HEADER_ALLOWED_PRINCIPALS to "",
                OidfConfigKeys.Cache.HTTP_RESOLVER_LOCALITY to "LOCAL_PREFERRED",
                OidfConfigKeys.Tenant.rootIdentifier("acme") to "https://acme.example",
                OidfConfigKeys.Tenant.kmsProvider("acme") to "azure",
                OidfConfigKeys.Tenant.cacheHttpResolverLocality("acme") to "DISTRIBUTED_ONLY",
                OidfConfigKeys.Tenant.cacheTrustChainLocality("acme") to "LOCAL_ONLY",
                OidfConfigKeys.Tenant.accountHeaderPrincipalClaim("acme") to "preferred_username",
                OidfConfigKeys.Tenant.accountHeaderAllowedPrincipals("acme") to "ops-acme,bot-acme",
            ),
        )

        assertEquals("https://default.example", binder.getFederationConfig().rootIdentifier)
        assertEquals("memory", binder.getKmsConfig().defaultProvider)

        val tenant = binder.getTenantConfig("acme")
        assertNotNull(tenant)
        assertEquals("https://acme.example", tenant.rootIdentifier)
        assertEquals("azure", tenant.kmsProvider)
        assertEquals("DISTRIBUTED_ONLY", tenant.cacheHttpResolverLocality)
        assertEquals("LOCAL_ONLY", tenant.cacheTrustChainLocality)
        assertEquals("preferred_username", tenant.accountHeaderPrincipalClaim)
        assertEquals(listOf("ops-acme", "bot-acme"), tenant.accountHeaderAllowedPrincipals)

        assertEquals("azure", binder.getEffectiveKmsConfig("acme").defaultProvider)
        assertEquals("memory", binder.getEffectiveKmsConfig(null).defaultProvider)

        val effectiveIdentity = binder.getEffectiveIdentityConfig("acme")
        assertEquals(IdentityMode.ACCOUNT, effectiveIdentity.mode)
        assertEquals("preferred_username", effectiveIdentity.accountHeaderPrincipalClaim)
        assertEquals(listOf("ops-acme", "bot-acme"), effectiveIdentity.accountHeaderAllowedPrincipals)

        assertEquals(
            "DISTRIBUTED_ONLY",
            binder.getEffectiveCacheLocality(OidfConfigKeys.Cache.HTTP_RESOLVER_LOCALITY, "acme"),
        )
        assertNull(binder.getTenantConfig("unknown"))
        assertTrue(OidfConfigFileLoader.tenantIdsFromFileSource().contains("acme"))
    }

    @Test
    fun tenant_keys_match_OidfConfigKeys() {
        assertEquals(
            "oidf.tenant.acme.federation.root.identifier",
            OidfConfigKeys.Tenant.rootIdentifier("acme"),
        )
        assertEquals(
            "oidf.tenant.acme.kms.provider",
            OidfConfigKeys.Tenant.kmsProvider("acme"),
        )
    }

    @Test
    fun session_tenant_config_bare_keys_win_over_catalog() {
        val session = mockk<com.sphereon.core.api.conf.ConfigService>(relaxed = true)
        every { session.getPropertyAsString(any(), any()) } answers {
            when (firstArg<String>()) {
                OidfConfigKeys.Kms.DEFAULT_PROVIDER -> "aws"
                OidfConfigKeys.Federation.ROOT_IDENTIFIER -> "https://session-root.example"
                else -> null
            }
        }

        OidfFilePropertySource.putAll(
            mapOf(
                OidfConfigKeys.Tenant.kmsProvider("t1") to "azure",
                OidfConfigKeys.Tenant.rootIdentifier("t1") to "https://catalog.example",
                OidfConfigKeys.Kms.DEFAULT_PROVIDER to "memory",
                OidfConfigKeys.Federation.ROOT_IDENTIFIER to "https://app.example",
            ),
        )

        assertEquals(
            "https://session-root.example",
            binder.getEffectiveFederationConfig("t1", session).rootIdentifier,
        )
        assertEquals("aws", binder.getEffectiveKmsConfig("t1", session).defaultProvider)
    }
}
