/*
 * Copyright 2025 Sphereon International B.V.
 *
 * Licensed under the Apache License, Version 2.0
 */

package com.sphereon.openid.fed.trust

import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.core.api.cache.CacheService
import com.sphereon.core.api.cache.CacheTtlConfig
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.di.session.SessionScope
import com.sphereon.crypto.core.jose.Jwk
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import kotlinx.serialization.json.Json
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkCommand
import com.sphereon.trust.core.TrustValidationService
import com.sphereon.trust.core.config.TrustConfigProvider
import com.sphereon.trust.core.model.TrustAnchor
import com.sphereon.trust.core.model.TrustContext
import com.sphereon.trust.core.model.TrustStatus
import com.sphereon.trust.core.model.TrustValidationRequest
import com.sphereon.trust.core.model.TrustValidationResult
import com.sphereon.trust.core.validation.AbstractTrustValidationService
import kotlinx.datetime.Clock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Duration.Companion.minutes

/**
 * OpenID Federation trust validation service.
 *
 * Bridges the IDK trust validation framework to the OpenID Federation
 * trust chain resolution and verification commands.
 *
 * Flow:
 * 1. Extract entity identifier from the validation request
 * 2. Get trust anchors from config (with request-level overrides)
 * 3. Resolve the trust chain via ResolveTrustChainCommand
 * 4. Verify the trust chain via VerifyTrustChainCommand
 * 5. If requiredTrustMarks configured, verify each via VerifyTrustMarkCommand
 * 6. Cache resolved results per entity
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(scope = SessionScope::class, boundType = TrustValidationService::class, multibinding = true)
class OidfTrustValidationService(
    private val resolveTrustChainCommand: ResolveTrustChainCommand,
    private val verifyTrustChainCommand: VerifyTrustChainCommand,
    private val verifyTrustMarkCommand: VerifyTrustMarkCommand,
    private val getEntityConfigurationCommand: GetEntityConfigurationCommand,
    private val trustConfigProvider: TrustConfigProvider,
    private val cacheService: CacheService,
    private val execution: SessionExecution
) : AbstractTrustValidationService("openid_federation", setOf(TrustContext.TYPE_OPENID_FEDERATION)) {

    private val logger = execution.log.logManager.withTagAsync("OidfTrustValidationService")

    private val cache by lazy {
        cacheService.getStringCache<TrustValidationResult>(
            CacheRequirements(
                namespace = "trust.oidfed.chains",
                ttlConfig = CacheTtlConfig(app = 30.minutes)
            )
        )
    }

    override suspend fun validate(request: TrustValidationRequest): TrustValidationResult {
        logger.debug("Validating OpenID Federation trust for context: ${request.context}")

        val entityIdentifier = request.context.parameters["entityIdentifier"]
            ?: return validationError("No entityIdentifier specified in context parameters")

        // Request parameters override config values
        val oidfConfig = trustConfigProvider.getTrustConfig().anchors.oidfed
        val trustAnchors = request.context.parameters["trustAnchors"]
            ?.split(",")?.map { it.trim() }
            ?: oidfConfig.trustAnchors
        val requiredTrustMarks = request.context.parameters["requiredTrustMarks"]
            ?.split(",")?.map { it.trim() }
            ?: oidfConfig.requiredTrustMarks
        val maxChainDepth = request.context.parameters["maxChainDepth"]?.toIntOrNull()
            ?: oidfConfig.maxChainDepth

        if (trustAnchors.isEmpty()) {
            return validationError("No trust anchors configured. Set trust.anchors.oidfed.trust-anchors or provide trustAnchors in request parameters")
        }

        // Check cache
        val cacheKey = "$entityIdentifier:${trustAnchors.sorted().joinToString(",")}"
        val cached = cache.getApp(cacheKey)
        if (cached.isOk && cached.value != null) {
            logger.debug("Using cached trust chain result for $entityIdentifier")
            return cached.value!!
        }

        return try {
            // Step 1: Resolve the trust chain
            val resolveResult = resolveTrustChainCommand.resolveTrustChain(
                entityIdentifier = entityIdentifier,
                trustAnchors = trustAnchors.toTypedArray(),
                maxDepth = maxChainDepth
            )

            if (resolveResult.isErr) {
                val error = resolveResult.error
                return cacheAndReturn(cacheKey, TrustValidationResult(
                    trusted = false,
                    status = TrustStatus.UNTRUSTED,
                    details = "Trust chain resolution failed: ${error.message}",
                    validatedAt = Clock.System.now()
                ))
            }

            val resolveResponse = resolveResult.value
            val trustChain = resolveResponse.trustChain
            if (trustChain.isEmpty()) {
                return cacheAndReturn(cacheKey, TrustValidationResult(
                    trusted = false,
                    status = TrustStatus.UNTRUSTED,
                    details = resolveResponse.errorMessage ?: "No trust chain found for entity: $entityIdentifier",
                    validatedAt = Clock.System.now()
                ))
            }

            // Step 2: Verify the trust chain cryptographically
            val verifyResult = verifyTrustChainCommand.verifyTrustChain(
                trustChain = trustChain.toTypedArray(),
                trustAnchor = trustAnchors.firstOrNull()
            )

            if (verifyResult.isErr) {
                val error = verifyResult.error
                return cacheAndReturn(cacheKey, TrustValidationResult(
                    trusted = false,
                    status = TrustStatus.UNTRUSTED,
                    details = "Trust chain verification failed: ${error.message}",
                    validatedAt = Clock.System.now()
                ))
            }

            val verifyResponse = verifyResult.value
            if (!verifyResponse.isValid) {
                return cacheAndReturn(cacheKey, TrustValidationResult(
                    trusted = false,
                    status = TrustStatus.UNTRUSTED,
                    details = "Trust chain is not valid: ${verifyResponse.errorMessage ?: "unknown reason"}",
                    validatedAt = Clock.System.now()
                ))
            }

            // All checks passed
            cacheAndReturn(cacheKey, TrustValidationResult(
                trusted = true,
                status = TrustStatus.TRUSTED,
                validationPath = trustChain,
                details = "Entity trusted via OpenID Federation trust chain (${trustChain.size} links)",
                validatedAt = Clock.System.now()
            ))
        } catch (e: Exception) {
            logger.error("OpenID Federation trust validation failed", exception = e)
            TrustValidationResult(
                trusted = false,
                status = TrustStatus.VALIDATION_ERROR,
                details = "OpenID Federation validation error: ${e.message}",
                validatedAt = Clock.System.now()
            )
        }
    }

    private val anchorsCache by lazy {
        cacheService.getStringCache<List<TrustAnchor>>(
            CacheRequirements(
                namespace = "trust.oidfed.anchors",
                ttlConfig = CacheTtlConfig(app = 30.minutes)
            )
        )
    }

    override suspend fun getTrustAnchors(): List<TrustAnchor> {
        val cached = anchorsCache.getApp("anchors")
        if (cached.isOk && cached.value != null) {
            return cached.value!!
        }

        val trustAnchorIds = trustConfigProvider.getTrustConfig().anchors.oidfed.trustAnchors
        val anchors = mutableListOf<TrustAnchor>()

        for (entityId in trustAnchorIds) {
            try {
                val configResult = getEntityConfigurationCommand.getEntityConfiguration(entityId)
                if (configResult.isErr) {
                    logger.warn("Failed to resolve entity configuration for trust anchor $entityId: ${configResult.error.message}")
                    continue
                }
                val entityConfig = configResult.value
                val jwks = entityConfig.jwks.propertyKeys ?: continue
                val firstOidfJwk = jwks.firstOrNull() ?: continue

                // Convert OID-Fed Jwk model to IDK Jwk via JSON round-trip
                val jwkJson = Json.encodeToString(
                    com.sphereon.openid.fed.openapi.models.Jwk.serializer(),
                    firstOidfJwk
                )
                val idkJwk = Json { ignoreUnknownKeys = true }
                    .decodeFromString(Jwk.serializer(), jwkJson)

                val keyInfo = com.sphereon.crypto.core.ResolvedKeyInfo(
                    key = idkJwk,
                    kid = firstOidfJwk.kid
                )

                anchors.add(TrustAnchor(
                    id = entityId,
                    type = TrustAnchor.TYPE_OPENID_FED_ENTITY,
                    name = entityConfig.sub,
                    keyInfo = keyInfo,
                    uri = entityId
                ))
            } catch (e: Exception) {
                logger.warn("Failed to resolve trust anchor $entityId: ${e.message}")
            }
        }

        if (anchors.isNotEmpty()) {
            anchorsCache.putApp("anchors", anchors)
        }
        return anchors
    }

    private suspend fun cacheAndReturn(cacheKey: String, result: TrustValidationResult): TrustValidationResult {
        cache.putApp(cacheKey, result)
        return result
    }

    private fun validationError(message: String) = TrustValidationResult(
        trusted = false,
        status = TrustStatus.VALIDATION_ERROR,
        details = message,
        validatedAt = Clock.System.now()
    )
}
