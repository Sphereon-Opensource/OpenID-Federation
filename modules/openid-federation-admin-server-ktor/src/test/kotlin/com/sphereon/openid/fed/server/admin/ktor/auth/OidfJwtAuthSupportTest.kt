package com.sphereon.openid.fed.server.admin.ktor.auth

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
import com.sphereon.openid.fed.core.tenant.IdentityMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [OidfJwtAuthSupport.shouldInstall] — admin always auth when issuer set.
 */
class OidfJwtAuthSupportTest {

    private class FakeBinder(
        private val identity: IdentityConfig,
        private val oauth: OAuth2Config,
    ) : OidfConfigBinder {
        override fun getFederationConfig() = FederationConfig()
        override fun getServerConfig(type: OidfConfigBinder.ServerType) = ServerConfig(port = 8081)
        override fun getCorsConfig() = CorsConfig()
        override fun getLoggerConfig() = LoggerConfig()
        override fun getDatasourceConfig() = DatasourceConfig()
        override fun getOAuth2Config() = oauth
        override fun getKmsConfig() = KmsConfig()
        override fun getIdentityConfig() = identity
        override fun getAppConfig() = OidfAppConfig(identity = identity, oauth2 = oauth)
        override fun getTenantConfig(tenantId: String): TenantConfig? = null
        override fun getProperty(key: String, default: String) = default
        override fun getBooleanProperty(key: String, default: Boolean) = default
        override fun getIntProperty(key: String, default: Int) = default
        override fun getLongProperty(key: String, default: Long) = default
        override fun getListProperty(key: String, default: List<String>) = default
    }

    @Test
    fun legacy_auto_installs_when_issuer_set() {
        val binder = FakeBinder(
            identity = IdentityConfig(mode = IdentityMode.ACCOUNT),
            oauth = OAuth2Config(
                issuerUri = "https://idk-test-issuer.local/oidc",
                jwtAuthEnabled = "auto",
            ),
        )
        assertTrue(OidfJwtAuthSupport.shouldInstall(binder))
    }

    @Test
    fun platform_auto_installs_when_issuer_set() {
        val binder = FakeBinder(
            identity = IdentityConfig(mode = IdentityMode.EXTERNAL),
            oauth = OAuth2Config(
                issuerUri = "https://idk-test-issuer.local/oidc",
                audience = "openid-federation-admin",
                jwtAuthEnabled = "auto",
            ),
        )
        assertTrue(OidfJwtAuthSupport.shouldInstall(binder))
    }

    @Test
    fun auto_skips_when_issuer_blank() {
        val binder = FakeBinder(
            identity = IdentityConfig(mode = IdentityMode.EXTERNAL),
            oauth = OAuth2Config(issuerUri = "", jwtAuthEnabled = "auto"),
        )
        assertFalse(OidfJwtAuthSupport.shouldInstall(binder))
    }

    @Test
    fun forced_true_requires_issuer() {
        val noIssuer = FakeBinder(
            identity = IdentityConfig(mode = IdentityMode.ACCOUNT),
            oauth = OAuth2Config(issuerUri = "", jwtAuthEnabled = "true"),
        )
        assertFalse(OidfJwtAuthSupport.shouldInstall(noIssuer))

        val withIssuer = FakeBinder(
            identity = IdentityConfig(mode = IdentityMode.ACCOUNT),
            oauth = OAuth2Config(
                issuerUri = "https://idk-test-issuer.local/oidc",
                jwtAuthEnabled = "true",
            ),
        )
        assertTrue(OidfJwtAuthSupport.shouldInstall(withIssuer))
    }

    @Test
    fun forced_false_never_installs() {
        val binder = FakeBinder(
            identity = IdentityConfig(mode = IdentityMode.EXTERNAL),
            oauth = OAuth2Config(
                issuerUri = "https://idk-test-issuer.local/oidc",
                jwtAuthEnabled = "false",
            ),
        )
        assertFalse(OidfJwtAuthSupport.shouldInstall(binder))
    }
}
