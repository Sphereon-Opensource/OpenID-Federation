package com.sphereon.openid.fed.integration.tests.platform

import com.sphereon.openid.fed.server.admin.api.http.describe.AdminHttpAdapterDescriptorProvider
import com.sphereon.openid.fed.server.federation.api.http.describe.FederationHttpAdapterDescriptorProvider
import com.sphereon.core.api.session.AppCommandInvoker
import com.sphereon.core.defaults.app.DefaultRootScopeProvider
import com.sphereon.di.app.AbstractAppGraph
import com.sphereon.di.app.RootScopeProvider
import com.sphereon.di.session.SessionScope
import com.sphereon.crypto.core.kms.model.KeyProviderSettings
import com.sphereon.crypto.kms.provider.azure.AzureKeyVaultCryptoProvider
import com.sphereon.crypto.kms.provider.azure.AzureKmsProviderConfig
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.tenant.TenantServiceConfig
import com.sphereon.oauth2.common.config.MutableOAuth2ServerInstanceIdProvider
import com.sphereon.oauth2.jwt.validation.JwtValidationConfig
import com.sphereon.oauth2.server.authorization.storage.SigningKeyStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import dev.zacsweers.metro.createGraphFactory

/** Consumer-generated graph keeps production bindings and exposes their real session selector. */
@DependencyGraph(AppScope::class, excludes = [AdminHttpAdapterDescriptorProvider::class, FederationHttpAdapterDescriptorProvider::class])
abstract class IdkOauth2AsTestGraph : AbstractAppGraph() {
    abstract val appCommandInvoker: AppCommandInvoker
    abstract val signingKeyStore: SigningKeyStore

    @Provides
    @SingleIn(AppScope::class)
    fun provideJwtValidationConfig(): JwtValidationConfig = JwtValidationConfig()

    // This consumer classpath also contains federation HTTP commands; match their owning server's Json binding.
    @Provides
    @SingleIn(AppScope::class)
    fun provideJson(): Json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideTenantServiceConfig(configBinder: OidfConfigBinder): TenantServiceConfig =
        TenantServiceConfig(configBinder.getFederationConfig().rootIdentifier)

    @Provides
    @SingleIn(AppScope::class)
    fun provideAzureKeyVaultCryptoProviderFactory(): (AzureKmsProviderConfig, KeyProviderSettings) -> AzureKeyVaultCryptoProvider =
        { config, _ -> AzureKeyVaultCryptoProvider(config) }

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Any,
            @Provides @Named("version") version: String,
            @Provides @Named("appId") appId: String,
            @Provides @Named("profile") profile: String,
            @Provides rootScopeProvider: RootScopeProvider,
        ): IdkOauth2AsTestGraph
    }
}

@ContributesTo(SessionScope::class)
interface IdkOauth2AsTestSessionGraph {
    val asInstanceIdProvider: MutableOAuth2ServerInstanceIdProvider
}

fun createIdkOauth2AsTestGraph(application: Any, appId: String, profile: String): IdkOauth2AsTestGraph =
    createGraphFactory<IdkOauth2AsTestGraph.Factory>().create(
        application = application,
        version = "test",
        appId = appId,
        profile = profile,
        rootScopeProvider = DefaultRootScopeProvider(),
    ).also { it.initRootScopeProvider() }
