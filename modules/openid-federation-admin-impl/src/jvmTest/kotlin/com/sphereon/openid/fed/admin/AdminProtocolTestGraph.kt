package com.sphereon.openid.fed.admin

import com.sphereon.core.defaults.app.DefaultRootScopeProvider
import com.sphereon.di.app.AbstractAppGraph
import com.sphereon.di.app.RootScopeProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory

/** Services-owned JVM fixture: IDK session, software KMS, and real JWS bindings. */
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
abstract class AdminProtocolTestGraph : AbstractAppGraph() {
    // Crypto graph can resolve JWKS URL services, but these key/signing tests never make HTTP requests.
    @Provides fun httpClientFactory(): com.sphereon.ktor.http.client.provider.HttpClientFactory = io.mockk.mockk()

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Any,
            @Provides @Named("appId") appId: String,
            @Provides @Named("profile") profile: String,
            @Provides @Named("version") version: String,
            @Provides rootScopeProvider: RootScopeProvider,
        ): AdminProtocolTestGraph
    }
}

fun createAdminProtocolTestGraph(application: Any): AdminProtocolTestGraph =
    createGraphFactory<AdminProtocolTestGraph.Factory>().create(
        application = application,
        appId = "exact-superior-evidence-test",
        profile = "test",
        version = "1.0.0",
        rootScopeProvider = DefaultRootScopeProvider(),
    ).also { it.initRootScopeProvider() }
