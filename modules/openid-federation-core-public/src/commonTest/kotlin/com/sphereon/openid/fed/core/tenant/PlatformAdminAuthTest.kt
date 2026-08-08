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
 * Config contract for admin identity modes.
 * Admin always requires Bearer; there is no anonymous-admin flag.
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
    fun account_and_external_modes_remain_distinct_for_tenant_selection() {
        val account = FakeBinder(IdentityConfig(mode = IdentityMode.ACCOUNT)).getIdentityConfig()
        val external = FakeBinder(IdentityConfig(mode = IdentityMode.EXTERNAL)).getIdentityConfig()
        assertTrue(account.isAccount)
        assertFalse(account.isExternal)
        assertTrue(external.isExternal)
        assertFalse(external.isAccount)
    }
}
