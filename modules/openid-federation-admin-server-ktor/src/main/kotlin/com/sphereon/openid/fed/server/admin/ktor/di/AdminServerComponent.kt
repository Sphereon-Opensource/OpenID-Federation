package com.sphereon.openid.fed.server.admin.ktor.di

import com.sphereon.core.defaults.app.DefaultRootScopeProvider
import com.sphereon.di.app.RootScopeProvider
import com.sphereon.crypto.core.kms.KmsProviderConfigBinder
import com.sphereon.crypto.core.kms.model.KeyProviderSettings
import com.sphereon.crypto.kms.keystore.memory.MemoryKeyStoreBackingStorage
import com.sphereon.crypto.kms.provider.azure.AzureKeyVaultCryptoProvider
import com.sphereon.crypto.kms.provider.azure.AzureKmsProviderConfig
import com.sphereon.di.app.AbstractAppGraph
import com.sphereon.core.api.log.Log
import com.sphereon.oauth2.jwt.validation.IdpConfig
import com.sphereon.oauth2.jwt.validation.JwtValidationConfig
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.config.OidfConfigBootstrap
import com.sphereon.openid.fed.core.tenant.TenantServiceConfig
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory
import kotlinx.serialization.json.Json

private val logger = Log.app().withTag("AdminServerAppGraph")

/**
 * Configuration for the Admin Server.
 *
 * This configuration is loaded via OidfConfigBinder which supports:
 * - IDK-normalized environment variables (OIDF_FEDERATION_ROOT_IDENTIFIER)
 * - Legacy environment variables (ROOT_IDENTIFIER, ADMIN_SERVER_PORT, etc.)
 * - reference.conf defaults
 */
data class AdminServerConfig(
    val rootIdentifier: String = "http://localhost:8080",
    val port: Int = 8081,
    val host: String = "0.0.0.0",
    val devMode: Boolean = false,
    val corsAllowedOrigins: List<String> = listOf("*"),
    val corsAllowedMethods: List<String> = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS"),
    val corsAllowedHeaders: List<String> = listOf("*"),
    val corsMaxAge: Long = 3600
)

/**
 * Main App-level DI graph for the Admin Server.
 *
 * Uses Metro's DependencyGraph to automatically include all contributed
 * bindings from IDK modules (KMS providers, services, etc.).
 */
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
abstract class AdminServerAppGraph : AbstractAppGraph() {
    abstract val configBinder: OidfConfigBinder
    abstract val kmsProviderConfigBinder: KmsProviderConfigBinder
    abstract val memoryKeyStoreBackingStorage: MemoryKeyStoreBackingStorage
    abstract val serverConfig: AdminServerConfig

    @Provides
    @SingleIn(AppScope::class)
    open fun provideAdminServerConfig(): AdminServerConfig {
        val federation = configBinder.getFederationConfig()
        val server = configBinder.getServerConfig(OidfConfigBinder.ServerType.ADMIN)
        val cors = configBinder.getCorsConfig()

        return AdminServerConfig(
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

    // CacheManager: use IDK's CacheManagerInitialization + KacheCacheModule contributions
    // (com.sphereon.core.api.cache.CacheManager). Do not re-provide an OIDF facade.

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

    /**
     * IDK JWT validation config for PLATFORM mode (and forced jwt auth).
     * When issuer URI is blank, validation stays disabled (LEGACY open-source default).
     */
    @Provides
    @SingleIn(AppScope::class)
    open fun provideJwtValidationConfig(): JwtValidationConfig {
        val oauth = configBinder.getOAuth2Config()
        val issuer = oauth.issuerUri.trim()
        if (issuer.isEmpty()) {
            return JwtValidationConfig(enabled = false)
        }
        val audience = oauth.audience.trim().takeIf { it.isNotEmpty() }
        val idp = IdpConfig.oidc(
            id = "oidf-primary",
            issuer = issuer,
            audience = audience,
        )
        return JwtValidationConfig(
            enabled = true,
            defaultIdp = idp,
            strictIssuerMatching = true,
        )
    }

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Any,
            @Provides @Named("appId") appId: String,
            @Provides @Named("profile") profile: String,
            @Provides @Named("version") version: String,
            @Provides rootScopeProvider: RootScopeProvider,
        ): AdminServerAppGraph
    }
}

/**
 * Create and initialize the AdminServerAppGraph.
 */
fun createAdminServerAppGraph(
    application: Any,
    appId: String = "openid-federation-admin-server",
    profile: String = "default",
    version: String = "1.0.0"
): AdminServerAppGraph {
    val graph = createGraphFactory<AdminServerAppGraph.Factory>().create(
        application = application,
        appId = appId,
        profile = profile,
        version = version,
        rootScopeProvider = DefaultRootScopeProvider()
    )
    // Registers Scoped instances + PropertySourceBootstrap (IDK Env + oidf-legacy-env bridge, …)
    graph.initRootScopeProvider()
    OidfConfigBootstrap.ensurePropertySourcesRegistered(graph)
    return graph
}
