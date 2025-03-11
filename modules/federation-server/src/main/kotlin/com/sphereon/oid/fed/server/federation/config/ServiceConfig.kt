package com.sphereon.oid.fed.server.federation.config

import com.sphereon.crypto.kms.EcDSACryptoProvider
import com.sphereon.crypto.kms.IKeyManagementSystem
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.AuthorityHintService
import com.sphereon.oid.fed.services.CriticalClaimService
import com.sphereon.oid.fed.services.EntityConfigurationStatementService
import com.sphereon.oid.fed.services.JwkService
import com.sphereon.oid.fed.services.JwtService
import com.sphereon.oid.fed.services.LogService
import com.sphereon.oid.fed.services.MetadataService
import com.sphereon.oid.fed.services.ReceivedTrustMarkService
import com.sphereon.oid.fed.services.ResolutionService
import com.sphereon.oid.fed.services.SubordinateService
import com.sphereon.oid.fed.services.TrustMarkService
import com.sphereon.oid.fed.services.config.AccountServiceConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

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
    open fun keyManagementSystem(): IKeyManagementSystem {
        return EcDSACryptoProvider()
    }

    @Bean
    open fun keyService(keyManagementSystem: IKeyManagementSystem): JwkService {
        return JwkService(keyManagementSystem)
    }

    @Bean
    open fun jwtService(keyManagementSystem: IKeyManagementSystem): JwtService {
        return JwtService(keyManagementSystem)
    }

    @Bean
    open fun subordinateService(
        accountService: AccountService,
        jwkService: JwkService,
        keyManagementSystem: IKeyManagementSystem
    ): SubordinateService {
        return SubordinateService(accountService, jwkService, keyManagementSystem)
    }

    @Bean
    open fun trustMarkService(
        jwkService: JwkService,
        keyManagementSystem: IKeyManagementSystem,
        accountService: AccountService
    ): TrustMarkService {
        return TrustMarkService(jwkService, keyManagementSystem, accountService)
    }

    @Bean
    open fun critService(): CriticalClaimService {
        return CriticalClaimService()
    }

    @Bean
    open fun entityConfigurationStatementService(
        accountService: AccountService,
        jwkService: JwkService,
        keyManagementSystem: IKeyManagementSystem
    ): EntityConfigurationStatementService {
        return EntityConfigurationStatementService(accountService, jwkService, keyManagementSystem)
    }

    @Bean
    open fun receivedTrustMarkService(): ReceivedTrustMarkService {
        return ReceivedTrustMarkService()
    }

    @Bean
    open fun resolveService(
        accountService: AccountService,
    ): ResolutionService {
        return ResolutionService(
            accountService
        )
    }
}
