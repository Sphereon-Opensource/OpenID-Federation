package com.sphereon.openid.fed.client.test

import com.sphereon.core.defaults.app.DefaultRootScopeProvider
import com.sphereon.di.app.RootScopeProvider
import com.sphereon.di.app.AbstractAppGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory

/**
 * App-level DI graph for the JS test module.
 *
 * IDK CacheManager is contributed by CacheManagerInitialization + KacheCacheModule.
 */
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
abstract class ClientTestAppGraph : AbstractAppGraph() {

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Any,
            @Provides @Named("appId") appId: String,
            @Provides @Named("profile") profile: String,
            @Provides @Named("version") version: String,
            @Provides rootScopeProvider: RootScopeProvider,
        ): ClientTestAppGraph
    }
}

fun createClientTestAppGraph(
    application: Any,
    appId: String = "federation-client-test-js",
    profile: String = "test",
    version: String = "1.0.0"
): ClientTestAppGraph {
    val graph = createGraphFactory<ClientTestAppGraph.Factory>().create(
        application = application,
        appId = appId,
        profile = profile,
        version = version,
        rootScopeProvider = DefaultRootScopeProvider()
    )
    graph.initRootScopeProvider()
    return graph
}
