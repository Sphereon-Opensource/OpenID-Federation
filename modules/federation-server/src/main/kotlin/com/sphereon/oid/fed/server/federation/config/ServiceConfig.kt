package com.sphereon.oid.fed.server.federation.config

import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.*
import com.sphereon.oid.fed.services.config.AccountServiceConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration
open class ServiceConfig {
    @Bean
    open fun accountConfig(environment: Environment): AccountServiceConfig {
        System.setProperty(
            "sphereon.federation.root-identifier",
            environment.getProperty("sphereon.federation.root-identifier", "http://localhost:8080")
        )
        return AccountServiceConfig()
    }

    @Bean
    open fun logService(): LogService {
        return LogService(Persistence.logQueries)
    }

    @Bean
    open fun entityConfigurationMetadataService(): EntityConfigurationMetadataService {
        return EntityConfigurationMetadataService()
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
    open fun keyService(kmsClient: KmsClient): KeyService {
        return KeyService(kmsClient)
    }

    @Bean
    open fun kmsClient(): KmsClient {
        return KmsService.getKmsClient()
    }

    @Bean
    open fun subordinateService(
        accountService: AccountService,
        keyService: KeyService,
        kmsClient: KmsClient
    ): SubordinateService {
        return SubordinateService(accountService, keyService, kmsClient)
    }

    @Bean
    open fun trustMarkService(
        keyService: KeyService,
        kmsClient: KmsClient,
        accountService: AccountService
    ): TrustMarkService {
        return TrustMarkService(keyService, kmsClient, accountService)
    }

    @Bean
    open fun critService(): CritService {
        return CritService()
    }

    @Bean
    open fun entityConfigurationStatementService(
        accountService: AccountService,
        keyService: KeyService,
        kmsClient: KmsClient
    ): EntityConfigurationStatementService {
        return EntityConfigurationStatementService(accountService, keyService, kmsClient)
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