package com.sphereon.openid.fed.integration.tests.platform

import com.sphereon.core.defaults.app.DefaultRootScopeProvider
import com.sphereon.crypto.kms.keystore.memory.MemoryKeyStoreBackingStorage
import com.sphereon.crypto.kms.keystore.memory.MemoryKeyStoreBackingStorageImpl
import com.sphereon.di.app.RootScopeProvider
import com.sphereon.openid.fed.server.admin.api.http.describe.AdminHttpAdapterDescriptorProvider
import com.sphereon.openid.fed.server.federation.api.http.describe.FederationHttpAdapterDescriptorProvider
import com.sphereon.openid.fed.core.config.OidfConfigBootstrap
import com.sphereon.openid.fed.server.admin.ktor.di.AdminServerBindings
import com.sphereon.openid.fed.server.federation.ktor.di.FederationServerBindings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory

/** Two real server graphs use one genuine software KMS backing store in this in-process fixture. */
@DependencyGraph(AppScope::class, excludes = [MemoryKeyStoreBackingStorageImpl::class, FederationHttpAdapterDescriptorProvider::class])
abstract class SharedKmsAdminTestGraph : AdminServerBindings() {
    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Any,
            @Provides @Named("appId") appId: String,
            @Provides @Named("profile") profile: String,
            @Provides @Named("version") version: String,
            @Provides rootScopeProvider: RootScopeProvider,
            @Provides backingStorage: MemoryKeyStoreBackingStorage,
        ): SharedKmsAdminTestGraph
    }
}

fun createSharedKmsAdminTestGraph(application: Any, appId: String, profile: String,
    backingStorage: MemoryKeyStoreBackingStorage): SharedKmsAdminTestGraph =
    createGraphFactory<SharedKmsAdminTestGraph.Factory>().create(
        application = application, appId = appId, profile = profile, version = "test",
        rootScopeProvider = DefaultRootScopeProvider(), backingStorage = backingStorage,
    ).also {
        it.initRootScopeProvider()
        OidfConfigBootstrap.ensurePropertySourcesRegistered(it)
    }

@DependencyGraph(AppScope::class, excludes = [MemoryKeyStoreBackingStorageImpl::class, AdminHttpAdapterDescriptorProvider::class])
abstract class SharedKmsFederationTestGraph : FederationServerBindings() {
    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Any,
            @Provides @Named("appId") appId: String,
            @Provides @Named("profile") profile: String,
            @Provides @Named("version") version: String,
            @Provides rootScopeProvider: RootScopeProvider,
            @Provides backingStorage: MemoryKeyStoreBackingStorage,
        ): SharedKmsFederationTestGraph
    }
}

fun createSharedKmsFederationTestGraph(application: Any, appId: String, profile: String,
    backingStorage: MemoryKeyStoreBackingStorage): SharedKmsFederationTestGraph =
    createGraphFactory<SharedKmsFederationTestGraph.Factory>().create(
        application = application, appId = appId, profile = profile, version = "test",
        rootScopeProvider = DefaultRootScopeProvider(), backingStorage = backingStorage,
    ).also {
        it.initRootScopeProvider()
        OidfConfigBootstrap.ensurePropertySourcesRegistered(it)
    }

