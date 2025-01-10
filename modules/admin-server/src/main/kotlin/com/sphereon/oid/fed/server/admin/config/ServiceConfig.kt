package com.sphereon.oid.fed.server.admin.config

import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.AuthorityHintService
import com.sphereon.oid.fed.services.CritService
import com.sphereon.oid.fed.services.EntityConfigurationMetadataService
import com.sphereon.oid.fed.services.EntityConfigurationStatementService
import com.sphereon.oid.fed.services.KeyService
import com.sphereon.oid.fed.services.LogService
import com.sphereon.oid.fed.services.ReceivedTrustMarkService
import com.sphereon.oid.fed.services.SubordinateService
import com.sphereon.oid.fed.services.TrustMarkService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
open class ServiceConfig {
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
    open fun accountService(): AccountService {
        return AccountService()
    }

    @Bean
    open fun keyService(): KeyService {
        return KeyService()
    }

    @Bean
    open fun subordinateService(): SubordinateService {
        return SubordinateService()
    }

    @Bean
    open fun trustMarkService(): TrustMarkService {
        return TrustMarkService()
    }

    @Bean
    open fun critService(): CritService {
        return CritService()
    }

    @Bean
    open fun entityConfigurationStatementService(): EntityConfigurationStatementService {
        return EntityConfigurationStatementService()
    }

    @Bean
    open fun receivedTrustMarkService(): ReceivedTrustMarkService {
        return ReceivedTrustMarkService()
    }
}
