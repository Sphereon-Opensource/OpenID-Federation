package com.sphereon.openid.fed.core.tenant

import com.sphereon.openid.fed.core.config.CorsConfig
import com.sphereon.openid.fed.core.config.DatasourceConfig
import com.sphereon.openid.fed.core.config.FederationConfig
import com.sphereon.openid.fed.core.config.KmsConfig
import com.sphereon.openid.fed.core.config.LoggerConfig
import com.sphereon.openid.fed.core.config.OAuth2Config
import com.sphereon.openid.fed.core.config.OidfAppConfig
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.config.ServerConfig
import com.sphereon.openid.fed.core.config.TenantConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for PLATFORM vs LEGACY admin auth policy.
 *
 * Full [com.sphereon.core.api.context.SessionExecution] integration is covered by
 * server-level tests; here we lock the config-driven branch conditions used by
 * [PlatformAdminAuth] before it consults session anonymity.
 */
class PlatformAdminAuthTest {

    private class FakeBinder(private val identity: IdentityConfig) : OidfConfigBinder {
        override fun getFederationConfig() = FederationConfig()
        override fun getServerConfig(type: OidfConfigBinder.ServerType) = ServerConfig(port = 8080)
        override fun getCorsConfig() = CorsConfig()
        override fun getLoggerConfig() = LoggerConfig()
        override fun getDatasourceConfig() = DatasourceConfig()
        override fun getOAuth2Config() = OAuth2Config()
        override fun getKmsConfig() = KmsConfig()
        override fun getIdentityConfig() = identity
        override fun getAppConfig() = OidfAppConfig(identity = identity)
        override fun getTenantConfig(tenantId: String): TenantConfig? = null
        override fun getProperty(key: String, default: String) = default
        override fun getBooleanProperty(key: String, default: Boolean) = default
        override fun getIntProperty(key: String, default: Int) = default
        override fun getLongProperty(key: String, default: Long) = default
        override fun getListProperty(key: String, default: List<String>) = default
    }

    @Test
    fun legacy_mode_skips_platform_auth_enforcement() {
        val cfg = FakeBinder(IdentityConfig(mode = IdentityMode.LEGACY)).getIdentityConfig()
        // PlatformAdminAuth returns null immediately when !isPlatform
        assertFalse(cfg.isPlatform)
        assertTrue(cfg.isLegacy)
    }

    @Test
    fun platform_defaults_to_rejecting_anonymous_admins() {
        val cfg = FakeBinder(
            IdentityConfig(mode = IdentityMode.PLATFORM, allowAnonymousAdmin = false),
        ).getIdentityConfig()
        assertTrue(cfg.isPlatform)
        assertFalse(cfg.allowAnonymousAdmin)
    }

    @Test
    fun platform_can_allow_anonymous_for_local_embeds() {
        val cfg = FakeBinder(
            IdentityConfig(mode = IdentityMode.PLATFORM, allowAnonymousAdmin = true),
        ).getIdentityConfig()
        assertTrue(cfg.isPlatform)
        assertTrue(cfg.allowAnonymousAdmin)
    }
}
