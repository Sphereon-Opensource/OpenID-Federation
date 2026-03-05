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
 * App-level DI component for the JS test module.
 */
@SingleIn(AppScope::class)
@MergeComponent(AppScope::class)
@Component
abstract class ClientTestAppComponent(
    application: Any,
    appId: String = "federation-client-test-js",
    profile: String = "test",
    version: String = "1.0.0"
) : ClientTestAppComponentMerged, AbstractAppComponent(
    application = application,
    appId = appId,
    profile = profile,
    version = version,
    rootScopeProvider = DefaultRootScopeProvider()
) {
    companion object {
        fun init(
            application: Any,
            appId: String = "federation-client-test-js",
            profile: String = "test",
            version: String = "1.0.0"
        ): ClientTestAppComponent {
            val component = ClientTestAppComponent::class.create(
                application,
                appId,
                profile,
                version
            )
            component.initRootScopeProvider()
            return component
        }
    }
}

/**
 * User context component for the JS test module.
 */
@SingleIn(UserScope::class)
@MergeComponent(UserScope::class)
@Component
abstract class ClientTestContextComponent(
    @Component val appComponent: ClientTestAppComponent
) : ClientTestContextComponentMerged

/**
 * Session-level DI component for the JS test module.
 */
@MergeComponent(SessionScope::class)
@SingleIn(SessionScope::class)
@Component
abstract class ClientTestSessionComponent(
    @Component val appComponent: ClientTestAppComponent,
    @Component val contextComponent: ClientTestContextComponent
) : ClientTestSessionComponentMerged
