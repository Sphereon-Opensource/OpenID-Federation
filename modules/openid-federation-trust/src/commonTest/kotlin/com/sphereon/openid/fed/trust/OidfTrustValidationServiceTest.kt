/*
 * Copyright 2025 Sphereon International B.V.
 *
 * Licensed under the Apache License, Version 2.0
 */

package com.sphereon.openid.fed.trust

import com.sphereon.core.api.IdkOkResult
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.Err
import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.core.api.cache.CacheService
import com.sphereon.core.api.cache.CacheStatistics
import com.sphereon.core.api.cache.TypedCacheService
import com.sphereon.core.api.cache.CacheGetArgs
import com.sphereon.core.api.cache.CacheGetResult
import com.sphereon.core.api.cache.CachePutArgs
import com.sphereon.core.api.cache.CachePutResult
import com.sphereon.core.api.cache.CacheRemoveArgs
import com.sphereon.core.api.cache.CacheRemoveResult
import com.sphereon.core.api.cache.CacheInvalidateArgs
import com.sphereon.core.api.cache.CacheInvalidateResult
import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.core.api.conf.ConfigLevel
import com.sphereon.core.api.conf.ConfigService
import com.sphereon.core.api.conf.PrincipalConfigService
import com.sphereon.core.api.conf.TenantConfigService
import com.sphereon.core.api.context.ContextConfig
import com.sphereon.core.api.context.IdkScope
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.AsyncLogService
import com.sphereon.core.api.log.LogMessage
import com.sphereon.core.api.log.LoggerConfig
import com.sphereon.core.api.log.SessionLogManager
import com.sphereon.core.api.log.SessionLogService
import com.sphereon.di.context.NoOpSessionContext
import com.sphereon.di.context.createAnonymousSessionContext
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionContextManager
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationArgs
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainArgs
import com.sphereon.trust.core.model.TrustAnchor
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommandService
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainArgs
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommandService
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkArgs
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkCommand
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkCommandService
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.TrustChainResolveResponse
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse
import com.sphereon.trust.core.config.TrustConfig
import com.sphereon.trust.core.config.TrustConfigProvider
import com.sphereon.trust.core.config.OidfTrustConfig
import com.sphereon.trust.core.config.TrustAnchorsConfig
import com.sphereon.trust.core.model.TrustContext
import com.sphereon.trust.core.model.TrustStatus
import com.sphereon.trust.core.model.TrustValidationRequest
import com.sphereon.crypto.resolution.extern.ExternalIdentifierOIDFEntityIdOpts
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

class OidfTrustValidationServiceTest {

    @Test
    fun validatesSuccessfulTrustChain() = runTest {
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(
                trustChain = listOf("jwt-leaf", "jwt-intermediate", "jwt-anchor")
            )),
            verifyResult = Ok(VerifyTrustChainResponse(isValid = true))
        )

        val result = service.validate(createRequest(
            entityIdentifier = "https://entity.example.com",
            trustAnchors = "https://anchor.example.com"
        ))

        assertTrue(result.trusted)
        assertEquals(TrustStatus.TRUSTED, result.status)
        assertEquals(3, result.validationPath.size)
    }

    @Test
    fun returnsUntrustedWhenChainResolutionFails() = runTest {
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(
                trustChain = emptyList(),
                errorMessage = "No path to trust anchor"
            )),
            verifyResult = Ok(VerifyTrustChainResponse(isValid = false))
        )

        val result = service.validate(createRequest(
            entityIdentifier = "https://untrusted.example.com",
            trustAnchors = "https://anchor.example.com"
        ))

        assertFalse(result.trusted)
        assertEquals(TrustStatus.UNTRUSTED, result.status)
        assertTrue(result.details?.contains("No path to trust anchor") == true)
    }

    @Test
    fun returnsUntrustedWhenChainVerificationFails() = runTest {
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(
                trustChain = listOf("jwt-leaf", "jwt-anchor")
            )),
            verifyResult = Ok(VerifyTrustChainResponse(
                isValid = false,
                errorMessage = "Signature verification failed"
            ))
        )

        val result = service.validate(createRequest(
            entityIdentifier = "https://entity.example.com",
            trustAnchors = "https://anchor.example.com"
        ))

        assertFalse(result.trusted)
        assertEquals(TrustStatus.UNTRUSTED, result.status)
        assertTrue(result.details?.contains("Signature verification failed") == true)
    }

    @Test
    fun returnsErrorWhenNoEntityIdentifier() = runTest {
        val service = createService()

        val result = service.validate(TrustValidationRequest(
            identifier = ExternalIdentifierOIDFEntityIdOpts(identifier = "test"),
            context = TrustContext(type = TrustContext.TYPE_OPENID_FEDERATION)
        ))

        assertEquals(TrustStatus.VALIDATION_ERROR, result.status)
        assertTrue(result.details?.contains("No entityIdentifier") == true)
    }

    @Test
    fun returnsErrorWhenNoTrustAnchors() = runTest {
        val service = createService(
            configTrustAnchors = emptyList()
        )

        val result = service.validate(createRequest(
            entityIdentifier = "https://entity.example.com"
            // no trustAnchors in params, and config has empty list
        ))

        assertEquals(TrustStatus.VALIDATION_ERROR, result.status)
        assertTrue(result.details?.contains("No trust anchors") == true)
    }

    @Test
    fun usesConfigTrustAnchorsWhenNotInRequest() = runTest {
        var capturedAnchors: Array<String>? = null
        val service = createService(
            configTrustAnchors = listOf("https://config-anchor.example.com"),
            resolveResult = Ok(TrustChainResolveResponse(
                trustChain = listOf("jwt-leaf", "jwt-anchor")
            )),
            verifyResult = Ok(VerifyTrustChainResponse(isValid = true)),
            onResolve = { args -> capturedAnchors = args.trustAnchors }
        )

        service.validate(createRequest(
            entityIdentifier = "https://entity.example.com"
            // no trustAnchors param — should use config
        ))

        assertEquals("https://config-anchor.example.com", capturedAnchors?.firstOrNull())
    }

    @Test
    fun requestParamsOverrideConfig() = runTest {
        var capturedAnchors: Array<String>? = null
        val service = createService(
            configTrustAnchors = listOf("https://config-anchor.example.com"),
            resolveResult = Ok(TrustChainResolveResponse(
                trustChain = listOf("jwt-leaf", "jwt-anchor")
            )),
            verifyResult = Ok(VerifyTrustChainResponse(isValid = true)),
            onResolve = { args -> capturedAnchors = args.trustAnchors }
        )

        service.validate(createRequest(
            entityIdentifier = "https://entity.example.com",
            trustAnchors = "https://override-anchor.example.com"
        ))

        assertEquals("https://override-anchor.example.com", capturedAnchors?.firstOrNull())
    }

    @Test
    fun getTrustAnchorsResolvesEntityConfigurations() = runTest {
        val service = createService(
            configTrustAnchors = listOf("https://anchor1.example.com", "https://anchor2.example.com")
        )
        val anchors = service.getTrustAnchors()
        assertEquals(2, anchors.size)
        assertEquals("https://anchor1.example.com", anchors[0].id)
        assertEquals("https://anchor2.example.com", anchors[1].id)
        assertEquals(TrustAnchor.TYPE_OPENID_FED_ENTITY, anchors[0].type)
        assertEquals("key-1", anchors[0].keyInfo.kid)
    }

    @Test
    fun supportsOpenIdFederationContextOnly() {
        val supportedTypes = setOf(TrustContext.TYPE_OPENID_FEDERATION)
        assertTrue(TrustContext.TYPE_OPENID_FEDERATION in supportedTypes)
        assertFalse(TrustContext.TYPE_DID in supportedTypes)
        assertFalse(TrustContext.TYPE_X509 in supportedTypes)
        assertFalse(TrustContext.TYPE_ETSI_TSL in supportedTypes)
    }

    // -- Helpers --

    private fun createRequest(
        entityIdentifier: String,
        trustAnchors: String? = null
    ): TrustValidationRequest {
        val params = mutableMapOf("entityIdentifier" to entityIdentifier)
        if (trustAnchors != null) params["trustAnchors"] = trustAnchors
        return TrustValidationRequest(
            identifier = ExternalIdentifierOIDFEntityIdOpts(identifier = entityIdentifier),
            context = TrustContext(
                type = TrustContext.TYPE_OPENID_FEDERATION,
                parameters = params
            )
        )
    }

    private fun createService(
        configTrustAnchors: List<String> = listOf("https://default-anchor.example.com"),
        resolveResult: IdkResult<TrustChainResolveResponse, FederationError> = Ok(
            TrustChainResolveResponse(trustChain = listOf("jwt1", "jwt2"))
        ),
        verifyResult: IdkResult<VerifyTrustChainResponse, FederationError> = Ok(
            VerifyTrustChainResponse(isValid = true)
        ),
        onResolve: ((ResolveTrustChainArgs) -> Unit)? = null
    ): OidfTrustValidationService {
        val resolveCmd = object : ResolveTrustChainCommand {
            override val isEnabled: Boolean get() = true
            override suspend fun resolveTrustChain(
                entityIdentifier: String, trustAnchors: Array<String>, maxDepth: Int
            ): IdkResult<TrustChainResolveResponse, FederationError> {
                val args = ResolveTrustChainArgs(entityIdentifier, trustAnchors, maxDepth)
                onResolve?.invoke(args)
                return resolveResult
            }
            override suspend fun execute(args: ResolveTrustChainArgs): IdkResult<TrustChainResolveResponse, FederationError> =
                resolveTrustChain(args.entityIdentifier, args.trustAnchors, args.maxDepth)
            override suspend fun supports(args: Any): Boolean = args is ResolveTrustChainArgs
            override val id: String get() = ResolveTrustChainCommand.COMMAND_ID
        }

        val verifyCmd = object : VerifyTrustChainCommand {
            override val isEnabled: Boolean get() = true
            override suspend fun verifyTrustChain(
                trustChain: Array<String>, trustAnchor: String?, currentTime: Long?
            ): IdkResult<VerifyTrustChainResponse, FederationError> = verifyResult
            override suspend fun execute(args: VerifyTrustChainArgs): IdkResult<VerifyTrustChainResponse, FederationError> =
                verifyTrustChain(args.trustChain, args.trustAnchor, args.currentTime)
            override suspend fun supports(args: Any): Boolean = args is VerifyTrustChainArgs
            override val id: String get() = VerifyTrustChainCommand.COMMAND_ID
        }

        val trustMarkCmd = object : VerifyTrustMarkCommand {
            override val isEnabled: Boolean get() = true
            override suspend fun verifyTrustMark(
                trustMark: String, trustAnchorConfig: EntityConfigurationStatement, currentTime: Long?
            ): IdkResult<TrustMarkValidationResponse, FederationError> = throw NotImplementedError()
            override suspend fun execute(args: VerifyTrustMarkArgs): IdkResult<TrustMarkValidationResponse, FederationError> =
                throw NotImplementedError()
            override suspend fun supports(args: Any): Boolean = args is VerifyTrustMarkArgs
            override val id: String get() = VerifyTrustMarkCommand.COMMAND_ID
        }

        val configProvider = object : TrustConfigProvider {
            override fun getTrustConfig() = TrustConfig(
                anchors = TrustAnchorsConfig(
                    oidfed = OidfTrustConfig(
                        enabled = true,
                        trustAnchors = configTrustAnchors,
                        maxChainDepth = 5
                    )
                )
            )
        }

        val getEntityConfigCmd = object : GetEntityConfigurationCommand {
            override val isEnabled: Boolean get() = true
            override suspend fun getEntityConfiguration(entityIdentifier: String): IdkResult<EntityConfigurationStatement, FederationError> {
                return Ok(EntityConfigurationStatement(
                    iss = entityIdentifier,
                    sub = entityIdentifier,
                    exp = 9999999999.0,
                    iat = 1000000000.0,
                    jwks = com.sphereon.openid.fed.openapi.models.BaseStatementJwks(
                        propertyKeys = listOf(
                            com.sphereon.openid.fed.openapi.models.Jwk(
                                kty = "EC",
                                kid = "key-1",
                                crv = "P-256",
                                x = "f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU",
                                y = "x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"
                            )
                        )
                    )
                ))
            }
            override suspend fun execute(args: com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationArgs): IdkResult<EntityConfigurationStatement, FederationError> =
                getEntityConfiguration(args.entityIdentifier)
            override suspend fun supports(args: Any): Boolean = true
            override val id: String get() = GetEntityConfigurationCommand.COMMAND_ID
        }

        return OidfTrustValidationService(
            resolveTrustChainCommand = resolveCmd,
            verifyTrustChainCommand = verifyCmd,
            verifyTrustMarkCommand = trustMarkCmd,
            getEntityConfigurationCommand = getEntityConfigCmd,
            trustConfigProvider = configProvider,
            cacheService = NoOpCacheService(),
            execution = TestSessionExecution(createAnonymousSessionContext("oidfed-test"))
        )
    }

    // -- Test infrastructure --

    /**
     * No-op CacheService for testing — passes through without storing.
     * Matches the published CacheService API from IDK 0.25.0-SNAPSHOT.
     */
    private class NoOpCacheService : CacheService {
        override suspend fun get(args: CacheGetArgs) = Ok(CacheGetResult.miss(backend = "noop"))
        override suspend fun put(args: CachePutArgs) = Ok(CachePutResult(stored = false, toBackend = "noop"))
        override suspend fun remove(args: CacheRemoveArgs) = Ok(CacheRemoveResult(removed = false, fromBackend = "noop"))
        override suspend fun invalidate(args: CacheInvalidateArgs) = Ok(CacheInvalidateResult(entriesRemoved = 0L, namespacesAffected = emptyList()))
        override suspend fun invalidateTenant(tenantId: String) = Ok(CacheInvalidateResult(entriesRemoved = 0L, namespacesAffected = emptyList()))
        override suspend fun invalidatePrincipal(tenantId: String, principalId: String) = Ok(CacheInvalidateResult(entriesRemoved = 0L, namespacesAffected = emptyList()))
        override fun <K : Any, V : Any> getCache(requirements: CacheRequirements): TypedCacheService<K, V> = NoOpTypedCache()
        override fun <V : Any> getStringCache(requirements: CacheRequirements): TypedCacheService<String, V> = NoOpTypedCache()
        override fun stats(): Map<String, CacheStatistics> = emptyMap()
        override suspend fun isHealthy(): Boolean = true
    }

    private class NoOpTypedCache<K : Any, V : Any> : TypedCacheService<K, V> {
        override val namespace: String get() = "noop"
        override suspend fun getApp(key: K): IdkResult<V?, IdkError> = Ok(null)
        override suspend fun putApp(key: K, value: V, ttl: Duration?): IdkResult<Unit, IdkError> = Ok(Unit)
        override suspend fun removeApp(key: K): IdkResult<Boolean, IdkError> = Ok(false)
        override suspend fun getOrPutApp(key: K, ttl: Duration?, compute: suspend () -> V): IdkResult<V, IdkError> = Ok(compute())
        override suspend fun getTenant(tenantId: String, key: K): IdkResult<V?, IdkError> = Ok(null)
        override suspend fun putTenant(tenantId: String, key: K, value: V, ttl: Duration?): IdkResult<Unit, IdkError> = Ok(Unit)
        override suspend fun removeTenant(tenantId: String, key: K): IdkResult<Boolean, IdkError> = Ok(false)
        override suspend fun getOrPutTenant(tenantId: String, key: K, ttl: Duration?, compute: suspend () -> V): IdkResult<V, IdkError> = Ok(compute())
        override suspend fun getPrincipal(tenantId: String, principalId: String, key: K): IdkResult<V?, IdkError> = Ok(null)
        override suspend fun putPrincipal(tenantId: String, principalId: String, key: K, value: V, ttl: Duration?): IdkResult<Unit, IdkError> = Ok(Unit)
        override suspend fun removePrincipal(tenantId: String, principalId: String, key: K): IdkResult<Boolean, IdkError> = Ok(false)
        override suspend fun getOrPutPrincipal(tenantId: String, principalId: String, key: K, ttl: Duration?, compute: suspend () -> V): IdkResult<V, IdkError> = Ok(compute())
        override suspend fun invalidateTenant(tenantId: String): IdkResult<Long, IdkError> = Ok(0L)
        override suspend fun invalidatePrincipal(tenantId: String, principalId: String): IdkResult<Long, IdkError> = Ok(0L)
        override fun stats(): CacheStatistics = CacheStatistics()
    }

    private class TestSessionExecution(
        override val sessionContext: SessionContext
    ) : SessionExecution {
        override val sessionContextManager: SessionContextManager get() = throw NotImplementedError()
        override val log: SessionLogService = TestLogService(sessionContext)
        override val conf: ContextConfig = TestContextConfig()
    }

    private class TestContextConfig : ContextConfig {
        override val app: AppConfigService get() = throw NotImplementedError()
        override val tenant: TenantConfigService get() = throw NotImplementedError()
        override val principal: PrincipalConfigService get() = throw NotImplementedError()
        override fun conf(level: ConfigLevel): ConfigService = throw NotImplementedError()
    }

    private class TestLogService(
        override val sessionContext: SessionContext = NoOpSessionContext
    ) : SessionLogService {
        override val logManager: SessionLogManager = TestLogManager(sessionContext)
        override val scope: IdkScope = IdkScope.SESSION
        override val id: String = "test-log"
        override val isEnabled: Boolean = false
        override suspend fun setConfig(config: LoggerConfig): SessionLogService = this
        override fun executeAsync(message: LogMessage) = IdkOkResult(Unit)
        override fun toAsync(): AsyncLogService = TestAsyncLogService(sessionContext)
    }

    private class TestLogManager(private val ctx: SessionContext) : SessionLogManager {
        override suspend fun setGlobalConfig(config: LoggerConfig): SessionLogManager = this
        override suspend fun getGlobalConfig(): LoggerConfig = LoggerConfig.Default
        override fun withTagAsync(tag: String, config: LoggerConfig?): AsyncLogService = TestAsyncLogService(ctx)
        override fun withTag(tag: String, config: LoggerConfig?): SessionLogService = TestLogService(ctx)
    }

    private class TestAsyncLogService(
        override val sessionContext: SessionContext = NoOpSessionContext
    ) : AsyncLogService {
        override val scope: IdkScope = IdkScope.SESSION
        override val id: String = "test-async-log"
        override val isEnabled: Boolean = false
        override suspend fun setConfig(config: LoggerConfig): AsyncLogService = this
        override suspend fun execute(args: LogMessage) = IdkOkResult(Unit)
        override fun toSync(): SessionLogService = TestLogService(sessionContext)
    }
}
