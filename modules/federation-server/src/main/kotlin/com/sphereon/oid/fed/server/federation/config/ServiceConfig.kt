package com.sphereon.oid.fed.server.federation.config

import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.KeyService
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
    open fun keyService(): KeyService {
        return KeyService()
    }

    @Bean
    open fun subordinateService(accountService: AccountService): SubordinateService {
        return SubordinateService(accountService)
    }

    @Bean
    open fun trustMarkService(): TrustMarkService {
        return TrustMarkService()
    }
}