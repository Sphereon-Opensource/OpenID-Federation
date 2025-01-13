package com.sphereon.oid.fed.server.federation.config

import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.KeyService
import com.sphereon.oid.fed.services.KmsClient
import com.sphereon.oid.fed.services.KmsService
import com.sphereon.oid.fed.services.SubordinateService
import com.sphereon.oid.fed.services.TrustMarkService
import com.sphereon.oid.fed.services.config.AccountConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
open class ServiceConfig {
    @Bean
    open fun accountConfig(): AccountConfig {
        return AccountConfig()
    }

    @Bean
    open fun accountService(accountConfig: AccountConfig): AccountService {
        return AccountService(accountConfig)
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