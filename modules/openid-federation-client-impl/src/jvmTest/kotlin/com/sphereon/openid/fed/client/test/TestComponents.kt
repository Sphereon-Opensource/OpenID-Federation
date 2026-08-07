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
 * App-level DI graph for the JVM test module.
 *
 * IDK [com.sphereon.core.api.cache.CacheManager] is contributed by
 * CacheManagerInitialization + KacheCacheModule (lib-core-api-default).
 */
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
abstract class ClientImplTestAppGraph : AbstractAppGraph() {

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Any,
            @Provides @Named("appId") appId: String,
            @Provides @Named("profile") profile: String,
            @Provides @Named("version") version: String,
            @Provides rootScopeProvider: RootScopeProvider,
        ): ClientImplTestAppGraph
    }
}

fun createClientImplTestAppGraph(
    application: Any,
    appId: String = "federation-client-impl-test",
    profile: String = "test",
    version: String = "1.0.0"
): ClientImplTestAppGraph {
    val graph = createGraphFactory<ClientImplTestAppGraph.Factory>().create(
        application = application,
        appId = appId,
        profile = profile,
        version = version,
        rootScopeProvider = DefaultRootScopeProvider()
    )
    graph.initRootScopeProvider()
    return graph
}
