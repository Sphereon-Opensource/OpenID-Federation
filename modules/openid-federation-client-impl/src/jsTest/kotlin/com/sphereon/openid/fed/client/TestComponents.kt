package com.sphereon.openid.fed.client

import com.sphereon.core.defaults.app.DefaultRootScopeProvider
import com.sphereon.di.app.RootScopeProvider
import com.sphereon.di.app.AbstractAppGraph
import com.sphereon.core.api.cache.CacheBackend
import com.sphereon.openid.fed.core.cache.CacheManager
import com.sphereon.openid.fed.core.cache.DefaultCacheManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory

@ContributesTo(AppScope::class)
interface TestCacheManagerGraph {
    @Provides
    @SingleIn(AppScope::class)
    fun provideCacheManager(backends: Set<CacheBackend>): CacheManager {
        val backend = backends.firstOrNull { it.capabilities.isLocal } ?: backends.first()
        return DefaultCacheManager(backend)
    }
}

@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
abstract class FederationTestAppGraph : AbstractAppGraph() {

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
