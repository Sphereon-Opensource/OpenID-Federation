package com.sphereon.openid.fed.client.test

import com.sphereon.core.defaults.app.DefaultRootScopeProvider
import com.sphereon.di.app.AbstractAppComponent
import com.sphereon.di.context.UserScope
import com.sphereon.di.session.SessionScope
import com.sphereon.core.api.cache.CacheBackend
import com.sphereon.openid.fed.core.cache.CacheManager
import com.sphereon.openid.fed.core.cache.DefaultCacheManager
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Provides CacheManager for test DI graph.
 */
@ContributesTo(AppScope::class)
interface TestCacheManagerComponent {
    @Provides
    @SingleIn(AppScope::class)
    fun provideCacheManager(backends: Set<CacheBackend>): CacheManager {
        val backend = backends.firstOrNull { it.capabilities.isLocal } ?: backends.first()
        return DefaultCacheManager(backend)
    }
}

/**
 * App-level DI component for the JVM test module.
 */
@SingleIn(AppScope::class)
@MergeComponent(AppScope::class)
@Component
abstract class ClientImplTestAppComponent(
    application: Any,
    appId: String = "federation-client-impl-test",
    profile: String = "test",
    version: String = "1.0.0"
) : ClientImplTestAppComponentMerged, AbstractAppComponent(
    application = application,
    appId = appId,
    profile = profile,
    version = version,
    rootScopeProvider = DefaultRootScopeProvider()
)

/**
 * User context component for the JVM test module.
 */
@SingleIn(UserScope::class)
@MergeComponent(UserScope::class)
@Component
abstract class ClientImplTestContextComponent(
    @Component val appComponent: ClientImplTestAppComponent
) : ClientImplTestContextComponentMerged

/**
 * Session-level DI component for the JVM test module.
 */
@MergeComponent(SessionScope::class)
@SingleIn(SessionScope::class)
@Component
abstract class ClientImplTestSessionComponent(
    @Component val appComponent: ClientImplTestAppComponent,
    @Component val contextComponent: ClientImplTestContextComponent
) : ClientImplTestSessionComponentMerged
