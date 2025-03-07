package com.sphereon.oid.fed.server.admin.config

import com.sphereon.crypto.kms.IKeyManagementSystem
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.AuthorityHintService
import com.sphereon.oid.fed.services.CritService
import com.sphereon.oid.fed.services.EntityConfigurationStatementService
import com.sphereon.oid.fed.services.JwkService
import com.sphereon.oid.fed.services.KmsProviderType
import com.sphereon.oid.fed.services.KmsService
import com.sphereon.oid.fed.services.LogService
import com.sphereon.oid.fed.services.MetadataService
import com.sphereon.oid.fed.services.ReceivedTrustMarkService
import com.sphereon.oid.fed.services.ResolveService
import com.sphereon.oid.fed.services.SubordinateService
import com.sphereon.oid.fed.services.TrustMarkService
import com.sphereon.oid.fed.services.config.AccountServiceConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

val logger = Logger.tag("ServiceConfig")

@Configuration
open class ServiceConfig {
    @Bean
    open fun accountConfig(environment: Environment): AccountServiceConfig {
        return AccountServiceConfig(
            environment.getProperty("sphereon.federation.root-identifier", "http://localhost:8080")
        )
    }

    @Bean
    open fun logService(): LogService {
        return LogService(Persistence.logQueries)
    }

    @Bean
    open fun entityConfigurationMetadataService(): MetadataService {
        return MetadataService()
    }

    @Bean
    open fun authorityHintService(): AuthorityHintService {
        return AuthorityHintService()
    }

    @Bean
    open fun accountService(accountServiceConfig: AccountServiceConfig): AccountService {
        return AccountService(accountServiceConfig)
    }

    @Bean
    open fun keyService(kmsProvider: IKeyManagementSystem): JwkService {
        return JwkService(kmsProvider)
    }

    @Bean
    open fun kmsProvider(environment: Environment): IKeyManagementSystem {
        val providerType = environment.getProperty("sphereon.federation.service.kms.provider", "memory")

        return when (KmsProviderType.fromString(providerType)) {
            KmsProviderType.AZURE -> {
                try {
                    KmsService.createAzureKms(
                        applicationId = environment.getRequiredProperty("sphereon.federation.azure.application-id"),
                        keyvaultUrl = environment.getRequiredProperty("sphereon.federation.azure.keyvault-url"),
                        tenantId = environment.getRequiredProperty("sphereon.federation.azure.tenant-id"),
                        clientId = environment.getRequiredProperty("sphereon.federation.azure.client-id"),
                        clientSecret = environment.getRequiredProperty("sphereon.federation.azure.client-secret"),
                        maxRetries = environment.getProperty(
                            "sphereon.federation.azure.max-retries",
                            Int::class.java,
                            10
                        ),
                        baseDelayInMS = environment.getProperty(
                            "sphereon.federation.azure.base-delay",
                            Long::class.java,
                            500L
                        ),
                        maxDelayInMS = environment.getProperty(
                            "sphereon.federation.azure.max-delay",
                            Long::class.java,
                            15000L
                        )
                    ).getKmsProvider()
                } catch (e: Exception) {
                    logger.error("Error initializing Azure KMS provider: ${e.message}")
                    throw e
                }
            }

            else -> KmsService.createMemoryKms().getKmsProvider()
        }
    }

    @Bean
    open fun subordinateService(
        accountService: AccountService,
        jwkService: JwkService,
        kmsProvider: IKeyManagementSystem
    ): SubordinateService {
        return SubordinateService(accountService, jwkService, kmsProvider)
    }

    @Bean
    open fun trustMarkService(
        jwkService: JwkService,
        kmsProvider: IKeyManagementSystem,
        accountService: AccountService
    ): TrustMarkService {
        return TrustMarkService(jwkService, kmsProvider, accountService)
    }

    @Bean
    open fun critService(): CritService {
        return CritService()
    }

    @Bean
    open fun entityConfigurationStatementService(
        accountService: AccountService,
        jwkService: JwkService,
        kmsProvider: IKeyManagementSystem
    ): EntityConfigurationStatementService {
        return EntityConfigurationStatementService(accountService, jwkService, kmsProvider)
    }

    @Bean
    open fun receivedTrustMarkService(): ReceivedTrustMarkService {
        return ReceivedTrustMarkService()
    }

    @Bean
    open fun resolveService(
        accountService: AccountService,
    ): ResolveService {
        return ResolveService(
            accountService
        )
    }
}
