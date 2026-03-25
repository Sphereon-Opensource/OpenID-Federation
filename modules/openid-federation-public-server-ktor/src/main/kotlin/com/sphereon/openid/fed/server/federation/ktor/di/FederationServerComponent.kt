package com.sphereon.openid.fed.server.federation.ktor.di

import com.sphereon.core.defaults.app.DefaultRootScopeProvider
import com.sphereon.di.app.RootScopeProvider
import com.sphereon.crypto.core.kms.KmsProviderConfigBinder
import com.sphereon.crypto.core.kms.model.KeyProviderSettings
import com.sphereon.crypto.kms.keystore.memory.MemoryKeyStoreBackingStorage
import com.sphereon.crypto.kms.provider.azure.AzureKeyVaultCryptoProvider
import com.sphereon.crypto.kms.provider.azure.AzureKmsProviderConfig
import com.sphereon.di.app.AbstractAppGraph
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.cache.CacheBackend
import com.sphereon.openid.fed.core.cache.CacheManager
import com.sphereon.openid.fed.core.cache.DefaultCacheManager
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.tenant.TenantServiceConfig
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory
import kotlinx.serialization.json.Json

private val logger = Log.app().withTag("FederationServerAppGraph")

/**
 * Configuration for the Federation Server.
 *
 * This configuration is loaded via OidfConfigBinder which supports:
 * - IDK-normalized environment variables (OIDF_SERVER_FEDERATION_PORT)
 * - Legacy environment variables (SERVER_PORT, ROOT_IDENTIFIER, etc.)
 * - reference.conf defaults
 */
data class FederationServerConfig(
    val rootIdentifier: String = "http://localhost:8080",
    val port: Int = 8080,
    val host: String = "0.0.0.0",
    val devMode: Boolean = false,
    val corsAllowedOrigins: List<String> = listOf("*"),
    val corsAllowedMethods: List<String> = listOf("GET", "POST", "OPTIONS"),
    val corsAllowedHeaders: List<String> = listOf("*"),
    val corsMaxAge: Long = 3600
)

/**
 * Main App-level DI graph for the Federation Server.
 *
 * Uses Metro's DependencyGraph to automatically include all contributed
 * bindings from IDK modules (KMS providers, services, etc.).
 */
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
abstract class FederationServerAppGraph : AbstractAppGraph() {
    abstract val configBinder: OidfConfigBinder
    abstract override val kmsProviderConfigBinder: KmsProviderConfigBinder
    abstract val memoryKeyStoreBackingStorage: MemoryKeyStoreBackingStorage
    abstract val serverConfig: FederationServerConfig

    @Provides
    @SingleIn(AppScope::class)
    open fun provideFederationServerConfig(): FederationServerConfig {
        val federation = configBinder.getFederationConfig()
        val server = configBinder.getServerConfig(OidfConfigBinder.ServerType.FEDERATION)
        val cors = configBinder.getCorsConfig()

        return FederationServerConfig(
            rootIdentifier = federation.rootIdentifier,
            port = server.port,
            host = server.host,
            devMode = federation.devMode,
            corsAllowedOrigins = cors.allowedOrigins,
            corsAllowedMethods = cors.allowedMethods,
            corsAllowedHeaders = cors.allowedHeaders,
            corsMaxAge = cors.maxAge
        )
    }

    @Provides
    @SingleIn(AppScope::class)
    open fun provideTenantServiceConfig(): TenantServiceConfig {
        val federation = configBinder.getFederationConfig()
        return TenantServiceConfig(federation.rootIdentifier)
    }

    @Provides
    @SingleIn(AppScope::class)
    open fun provideCacheManager(backends: Set<CacheBackend>): CacheManager {
        val backend = backends.firstOrNull { it.capabilities.isLocal } ?: backends.first()
        logger.info("Initializing CacheManager for federation server with ${backend.id} backend")
        return DefaultCacheManager(backend)
    }

    @Provides
    @SingleIn(AppScope::class)
    open fun provideJson(): Json {
        return Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    @Provides
    @SingleIn(AppScope::class)
    open fun provideAzureKeyVaultCryptoProviderFactory(): (AzureKmsProviderConfig, KeyProviderSettings) -> AzureKeyVaultCryptoProvider {
        return { config, _ -> AzureKeyVaultCryptoProvider(config) }
    }

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Any,
            @Provides @Named("appId") appId: String,
            @Provides @Named("profile") profile: String,
            @Provides @Named("version") version: String,
            @Provides rootScopeProvider: RootScopeProvider,
        ): FederationServerAppGraph
    }
}

/**
 * Create and initialize the FederationServerAppGraph.
 */
fun createFederationServerAppGraph(
    application: Any,
    appId: String = "openid-federation-server",
    profile: String = "default",
    version: String = "1.0.0"
): FederationServerAppGraph {
    val graph = createGraphFactory<FederationServerAppGraph.Factory>().create(
        application = application,
        appId = appId,
        profile = profile,
        version = version,
        rootScopeProvider = DefaultRootScopeProvider()
    )
    graph.initRootScopeProvider()
    return graph
}
