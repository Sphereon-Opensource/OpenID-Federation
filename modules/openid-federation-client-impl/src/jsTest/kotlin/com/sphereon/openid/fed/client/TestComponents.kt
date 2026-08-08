package com.sphereon.openid.fed.client

import com.sphereon.core.defaults.app.DefaultRootScopeProvider
import com.sphereon.di.app.RootScopeProvider
import com.sphereon.di.app.AbstractAppGraph
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
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory

/**
 * App-level DI graph for JS client tests.
 *
 * IDK CacheManager is contributed by CacheManagerInitialization + KacheCacheModule.
 */
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
abstract class FederationTestAppGraph : AbstractAppGraph() {

    @Provides
    @SingleIn(AppScope::class)
    fun provideOidfConfigBinder(): OidfConfigBinder = object : OidfConfigBinder {
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

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Any,
            @Provides @Named("appId") appId: String,
            @Provides @Named("profile") profile: String,
            @Provides @Named("version") version: String,
            @Provides rootScopeProvider: RootScopeProvider,
        ): FederationTestAppGraph
    }
}

fun createFederationTestAppGraph(
    application: Any,
    appId: String = "federation-client-test",
    profile: String = "test",
    version: String = "1.0.0"
): FederationTestAppGraph {
    val graph = createGraphFactory<FederationTestAppGraph.Factory>().create(
        application = application,
        appId = appId,
        profile = profile,
        version = version,
        rootScopeProvider = DefaultRootScopeProvider()
    )
    graph.initRootScopeProvider()
    return graph
}
