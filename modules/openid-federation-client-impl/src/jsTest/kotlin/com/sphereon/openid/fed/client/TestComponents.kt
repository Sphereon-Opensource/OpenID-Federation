package com.sphereon.openid.fed.client

import com.sphereon.core.defaults.app.DefaultRootScopeProvider
import com.sphereon.di.app.AbstractAppComponent
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
 *
 * This binding is needed because the client-impl module requires CacheManager
 * but doesn't provide a default implementation - it's expected to be provided
 * by the consuming application.
 */
@ContributesTo(AppScope::class)
interface TestCacheManagerComponent {
    @Provides
    @SingleIn(AppScope::class)
    fun provideCacheManager(): CacheManager = DefaultCacheManager()
}


/**
 * Test application component for JS tests.
 *
 * This component merges all contributed bindings from AppScope and provides
 * the test infrastructure needed for FederationClient tests.
 */
@SingleIn(AppScope::class)
@MergeComponent(AppScope::class)
@Component
abstract class FederationTestAppComponent(
    application: Any,
    appId: String,
    profile: String,
    version: String
) : FederationTestAppComponentMerged, AbstractAppComponent(
    application = application,
    version = version,
    appId = appId,
    profile = profile,
    rootScopeProvider = DefaultRootScopeProvider()
) {
    companion object {
        /**
         * Creates and initializes the test app component.
         */
        fun init(
            application: Any,
            appId: String = "federation-client-test",
            profile: String = "test",
            version: String = "1.0.0"
        ): FederationTestAppComponent {
            val component = FederationTestAppComponent::class.create(
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
