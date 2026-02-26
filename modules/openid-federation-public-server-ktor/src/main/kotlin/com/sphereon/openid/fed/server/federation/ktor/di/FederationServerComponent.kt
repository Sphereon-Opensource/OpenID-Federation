package com.sphereon.openid.fed.server.federation.ktor.di

import com.sphereon.core.defaults.app.DefaultRootScopeProvider
import com.sphereon.crypto.core.kms.model.KeyProviderSettings
import com.sphereon.crypto.kms.provider.azure.AzureKeyVaultCryptoProvider
import com.sphereon.crypto.kms.provider.azure.AzureKmsProviderConfig
import com.sphereon.di.app.AbstractAppComponent
import com.sphereon.di.context.UserScope
import com.sphereon.di.session.SessionScope
import com.sphereon.core.api.log.Log
import com.sphereon.openid.fed.core.cache.CacheManager
import com.sphereon.openid.fed.core.cache.DefaultCacheManager
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.server.federation.api.http.command.FederationAccountResolver
import com.sphereon.openid.fed.services.AccountService
import com.sphereon.openid.fed.services.EntityConfigurationStatementService
import com.sphereon.openid.fed.services.JwkService
import com.sphereon.openid.fed.services.LogService
import com.sphereon.openid.fed.services.ResolutionService
import com.sphereon.openid.fed.services.SubordinateService
import com.sphereon.openid.fed.services.TrustMarkService
import com.sphereon.openid.fed.services.config.AccountServiceConfig
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

private val logger = Log.app().withTag("FederationServerComponent")

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
 * Main App-level DI component for the Federation Server.
 *
 * This component uses IDK's MergeComponent pattern to automatically include
 * all contributed bindings from IDK modules (KMS providers, services, etc.).
 *
 * Configuration is loaded via OidfConfigBinder which is auto-wired via
 * ContributesBinding from the services-impl module.
 */
@SingleIn(AppScope::class)
@MergeComponent(AppScope::class)
@Component
abstract class FederationServerAppComponent(
    application: Any,
    appId: String = "openid-federation-server",
    profile: String = "default",
    version: String = "1.0.0",
) : FederationServerAppComponentMerged, AbstractAppComponent(
    application = application,
    appId = appId,
    profile = profile,
    version = version,
    rootScopeProvider = DefaultRootScopeProvider()
) {
    /**
     * Configuration binder for accessing typed configuration.
     * Auto-wired via ContributesBinding from OidfConfigBinderImpl.
     */
    abstract val configBinder: OidfConfigBinder

    /**
     * Federation server configuration.
     * Use this property to access configuration after component initialization.
     */
    abstract val serverConfig: FederationServerConfig

    companion object {
        /**
         * Initialize the FederationServerAppComponent.
         * This creates the component and initializes the root scope provider.
         */
        fun init(
            application: Any,
            appId: String = "openid-federation-server",
            profile: String = "default",
            version: String = "1.0.0"
        ): FederationServerAppComponent {
            val component = FederationServerAppComponent::class.create(
                application,
                appId,
                profile,
                version
            )
            component.initRootScopeProvider()
            return component
        }
    }

    /**
     * Provides FederationServerConfig built from OidfConfigBinder.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideFederationServerConfig(): FederationServerConfig {
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

    /**
     * Provides configuration for AccountService.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideAccountServiceConfig(): AccountServiceConfig {
        val federation = configBinder.getFederationConfig()
        return AccountServiceConfig(federation.rootIdentifier)
    }

    /**
     * Provides the CacheManager for creating and managing scoped caches.
     * This is app-scoped so all sessions share the same cache manager.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideCacheManager(): CacheManager {
        logger.info("Initializing CacheManager for federation server")
        return DefaultCacheManager()
    }

    /**
     * Provides the Json serializer for the federation server.
     * Configured with lenient parsing and pretty printing for development.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideJson(): Json {
        return Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    /**
     * Provides the factory function for creating Azure Key Vault crypto providers.
     * The provider is created when Azure KMS is configured.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideAzureKeyVaultCryptoProviderFactory(): (AzureKmsProviderConfig, KeyProviderSettings) -> AzureKeyVaultCryptoProvider {
        return { config, _ -> AzureKeyVaultCryptoProvider(config) }
    }
}

/**
 * User context component for the Federation Server.
 */
@SingleIn(UserScope::class)
@MergeComponent(UserScope::class)
@Component
abstract class FederationServerContextComponent(
    @Component
    val appComponent: FederationServerAppComponent
) : FederationServerContextComponentMerged

// Note: All federation services are now auto-wired via @ContributesBinding annotations
// on the *ServiceImpl classes in openid-federation-services-impl module.

/**
 * Session-level DI component for the Federation Server.
 *
 * This component provides access to session-scoped services including
 * the KeyManagerService for cryptographic operations and all federation services.
 *
 * Note: We don't declare abstract properties here to avoid conflicts with
 * IDK's merged component bindings. Instead, we provide a FederationServices
 * instance that wraps all federation-specific services.
 */
@MergeComponent(SessionScope::class)
@SingleIn(SessionScope::class)
@Component
abstract class FederationServerSessionComponent(
    @Component
    val appComponent: FederationServerAppComponent,
    @Component
    val contextComponent: FederationServerContextComponent
) : FederationServerSessionComponentMerged {

    /**
     * Provides the aggregate FederationServices instance.
     */
    abstract val federationServices: FederationServices

    /**
     * Provides FederationServices aggregate containing all federation services.
     */
    @Provides
    fun provideFederationServices(
        accountService: AccountService,
        jwkService: JwkService,
        subordinateService: SubordinateService,
        trustMarkService: TrustMarkService,
        entityConfigurationStatementService: EntityConfigurationStatementService,
        resolutionService: ResolutionService,
        logService: LogService
    ): FederationServices {
        return FederationServicesImpl(
            accountService = accountService,
            jwkService = jwkService,
            subordinateService = subordinateService,
            trustMarkService = trustMarkService,
            entityConfigurationStatementService = entityConfigurationStatementService,
            resolutionService = resolutionService,
            logService = logService
        )
    }

    /**
     * Provides FederationAccountResolver for resolving accounts from HTTP requests.
     */
    @Provides
    fun provideFederationAccountResolver(accountService: AccountService): FederationAccountResolver {
        return FederationAccountResolver(accountService)
    }
}

/**
 * Interface for accessing federation services from a session instance.
 */
interface FederationServices {
    val accountService: AccountService
    val jwkService: JwkService
    val subordinateService: SubordinateService
    val trustMarkService: TrustMarkService
    val entityConfigurationStatementService: EntityConfigurationStatementService
    val resolutionService: ResolutionService
    val logService: LogService
}

/**
 * Implementation of FederationServices that holds all service instances.
 */
data class FederationServicesImpl(
    override val accountService: AccountService,
    override val jwkService: JwkService,
    override val subordinateService: SubordinateService,
    override val trustMarkService: TrustMarkService,
    override val entityConfigurationStatementService: EntityConfigurationStatementService,
    override val resolutionService: ResolutionService,
    override val logService: LogService
) : FederationServices

/**
 * Extension function to access federation services from session component.
 */
fun FederationServerSessionComponent.asFederationServices(): FederationServices {
    return this.federationServices
}
