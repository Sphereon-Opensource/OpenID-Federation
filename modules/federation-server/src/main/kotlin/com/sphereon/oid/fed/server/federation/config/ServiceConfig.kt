package com.sphereon.oid.fed.server.federation.config

import com.sphereon.oid.fed.services.*
import com.sphereon.oid.fed.services.config.AccountServiceConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
open class ServiceConfig {
    @Bean
    open fun accountConfig(): AccountServiceConfig {
        return AccountServiceConfig()
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
}