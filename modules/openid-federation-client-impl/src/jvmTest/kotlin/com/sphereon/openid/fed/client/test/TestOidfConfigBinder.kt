package com.sphereon.openid.fed.client.test

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
import com.sphereon.openid.fed.core.tenant.IdentityConfig

/**
 * Minimal [OidfConfigBinder] for client-impl JVM tests (FederationContext needs it for cache locality).
 */
class TestOidfConfigBinder : OidfConfigBinder {
    override fun getFederationConfig() = FederationConfig()
    override fun getServerConfig(type: OidfConfigBinder.ServerType) = ServerConfig(port = 8080)
    override fun getCorsConfig() = CorsConfig()
    override fun getLoggerConfig() = LoggerConfig()
    override fun getDatasourceConfig() = DatasourceConfig()
    override fun getOAuth2Config() = OAuth2Config()
    override fun getKmsConfig() = KmsConfig()
    override fun getIdentityConfig() = IdentityConfig()
    override fun getAppConfig() = OidfAppConfig()
    override fun getTenantConfig(tenantId: String): TenantConfig? = null
    override fun getProperty(key: String, default: String) = default
    override fun getBooleanProperty(key: String, default: Boolean) = default
    override fun getIntProperty(key: String, default: Int) = default
    override fun getLongProperty(key: String, default: Long) = default
    override fun getListProperty(key: String, default: List<String>) = default
}
