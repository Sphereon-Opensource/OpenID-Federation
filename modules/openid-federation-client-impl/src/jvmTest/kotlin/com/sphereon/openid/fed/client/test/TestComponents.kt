package com.sphereon.openid.fed.client.test

import com.sphereon.core.defaults.app.DefaultRootScopeProvider
import com.sphereon.di.app.RootScopeProvider
import com.sphereon.di.app.AbstractAppGraph
import com.sphereon.core.api.cache.CacheBackend
import com.sphereon.openid.fed.core.cache.CacheManager
import com.sphereon.openid.fed.core.cache.DefaultCacheManager
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory

/**
 * Provides CacheManager for test DI graph.
 */
@ContributesTo(AppScope::class)
interface TestCacheManagerGraph {
    @Provides
    @SingleIn(AppScope::class)
    fun provideCacheManager(backends: Set<CacheBackend>): CacheManager {
        val backend = backends.firstOrNull { it.capabilities.isLocal } ?: backends.first()
        return DefaultCacheManager(backend)
    }
}

/**
 * App-level DI graph for the JVM test module.
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
