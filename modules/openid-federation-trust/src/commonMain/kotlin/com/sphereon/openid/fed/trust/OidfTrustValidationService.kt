/*
 * Copyright 2025 Sphereon International B.V.
 *
 * Licensed under the Apache License, Version 2.0
 */

package com.sphereon.openid.fed.trust

import com.sphereon.core.api.cache.CacheManager
import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.core.api.cache.CacheSerializers
import com.sphereon.core.api.cache.CacheTtlConfig
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.di.session.SessionScope
import com.sphereon.crypto.core.jose.Jwk
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkCommand
import com.sphereon.trust.core.TrustValidationService
import com.sphereon.trust.core.config.TrustConfigProvider
import com.sphereon.trust.core.model.TrustAnchor
import com.sphereon.trust.core.model.TrustChain
import com.sphereon.trust.core.model.TrustChainLinks
import com.sphereon.trust.core.model.TrustContext
import com.sphereon.trust.core.model.TrustStatus
import com.sphereon.trust.core.model.TrustValidationRequest
import com.sphereon.trust.core.model.TrustValidationResult
import com.sphereon.trust.core.model.EntityDiscoveryOptions
import com.sphereon.trust.core.validation.AbstractTrustValidationService
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

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
 * 6. Cache results per effective request, bounded by signed evidence expiry
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesIntoSet(scope = SessionScope::class, binding = binding<TrustValidationService>())
class OidfTrustValidationService(
    private val resolveTrustChainCommand: ResolveTrustChainCommand,
    private val verifyTrustChainCommand: VerifyTrustChainCommand,
    private val verifyTrustMarkCommand: VerifyTrustMarkCommand,
    private val getEntityConfigurationCommand: GetEntityConfigurationCommand,
    private val trustConfigProvider: TrustConfigProvider,
    private val cacheManager: CacheManager,
    private val execution: SessionExecution,
    private val entityInfoExtractor: OidfEntityInfoExtractor
) : AbstractTrustValidationService("openid_federation", setOf(TrustContext.TYPE_OPENID_FEDERATION)) {

    private val logger = execution.log.logManager.withTagAsync("OidfTrustValidationService")

    private val cache by lazy {
        cacheManager.createStringCache(
            CacheRequirements(
                namespace = "trust.oidfed.chains",
                ttlConfig = CacheTtlConfig(app = 30.minutes)
            ),
            CacheSerializers.json<TrustValidationResult>()
        )
    }

    override suspend fun doValidate(request: TrustValidationRequest): TrustValidationResult {
        logger.debug("Validating OpenID Federation trust for context: ${request.context}")

        val entityIdentifier = request.context.parameters["entityIdentifier"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return validationError("No entityIdentifier specified in context parameters")

        // Request parameters override config values
        val oidfConfig = trustConfigProvider.getTrustConfig().anchors.oidfed
        val trustAnchors = request.context.parameters["trustAnchors"]
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: oidfConfig.trustAnchors.map { it.trim() }.filter { it.isNotEmpty() }
        val requiredTrustMarks = request.context.parameters["requiredTrustMarks"]
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: oidfConfig.requiredTrustMarks.map { it.trim() }.filter { it.isNotEmpty() }
        val maxChainDepth = request.context.parameters["maxChainDepth"]?.toIntOrNull()
            ?: oidfConfig.maxChainDepth

        if (trustAnchors.isEmpty()) {
            return validationError("No trust anchors configured. Set trust.anchors.oidfed.trust-anchors or provide trustAnchors in request parameters")
        }

        // The structured identity retains anchor preference and every request
        // input that can affect verification or result enrichment.
        val cacheKey = cacheKey(request, entityIdentifier, trustAnchors, requiredTrustMarks, maxChainDepth)
        val cached = cache.getApp(cacheKey)
        if (cached != null) {
            val expiry = cached.expiresAt
            if ((expiry == null && !cached.trusted) || (expiry != null && expiry > Clock.System.now())) {
                logger.debug("Using cached trust chain result for $entityIdentifier")
                return cached
            }
            cache.removeApp(cacheKey)
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

            // Verification authenticates these statements; decoding here only
            // extracts the terminal identifier and the signed lifetime bound.
            val statements = trustChain.mapNotNull { jwt ->
                runCatching { decodeJWTComponents(jwt).payload }.getOrNull()
            }
            val statementExpiries = statements.map { signedExpiry(it) }
            val now = Clock.System.now()
            if (statements.size != trustChain.size || statementExpiries.any { it == null || it <= now.epochSeconds }) {
                return cacheAndReturn(cacheKey, TrustValidationResult(
                    trusted = false,
                    status = TrustStatus.UNTRUSTED,
                    validationPath = trustChain,
                    details = "Trust chain contains malformed or expired signed evidence",
                    validatedAt = now
                ))
            }
            var evidenceExpiry = statementExpiries.filterNotNull().minOrNull()!!

            // Step 2: Verify the trust chain against the selected chain anchor,
            // not the first configured identifier.
            val selectedAnchor = selectedTrustAnchor(trustChain, trustAnchors)
                ?: return cacheAndReturn(cacheKey, TrustValidationResult(
                    trusted = false,
                    status = TrustStatus.UNTRUSTED,
                    validationPath = trustChain,
                    details = "No selected trust anchor on the resolved chain",
                    validatedAt = Clock.System.now()
                ))
            val verifyResult = verifyTrustChainCommand.verifyTrustChain(
                trustChain = trustChain.toTypedArray(),
                trustAnchor = selectedAnchor
            )

            if (verifyResult.isErr) {
                val error = verifyResult.error
                return cacheAndReturn(cacheKey, TrustValidationResult(
                    trusted = false,
                    status = TrustStatus.UNTRUSTED,
                    validationPath = trustChain,
                    trustChain = TrustChain.fromEntityStatementEntries(trustChain, TrustChainLinks.BROKEN),
                    details = "Trust chain verification failed: ${error.message}",
                    validatedAt = Clock.System.now()
                ))
            }

            val verifyResponse = verifyResult.value
            if (!verifyResponse.isValid) {
                return cacheAndReturn(cacheKey, TrustValidationResult(
                    trusted = false,
                    status = TrustStatus.UNTRUSTED,
                    validationPath = trustChain,
                    trustChain = TrustChain.fromEntityStatementEntries(trustChain, TrustChainLinks.BROKEN),
                    details = "Trust chain is not valid: ${verifyResponse.errorMessage ?: "unknown reason"}",
                    validatedAt = Clock.System.now()
                ))
            }

            if (requiredTrustMarks.isNotEmpty()) {
                val subjectConfig = getEntityConfigurationCommand.getEntityConfiguration(entityIdentifier)
                if (subjectConfig.isErr || subjectConfig.value.sub != entityIdentifier ||
                    !subjectConfig.value.exp.isFinite() || subjectConfig.value.exp <= Clock.System.now().epochSeconds
                ) {
                    return cacheAndReturn(cacheKey, TrustValidationResult(
                        trusted = false,
                        status = TrustStatus.UNTRUSTED,
                        validationPath = trustChain,
                        details = "Could not resolve the subject's advertised trust marks",
                        validatedAt = Clock.System.now()
                    ))
                }
                val taConfig = getEntityConfigurationCommand.getEntityConfiguration(selectedAnchor)
                if (taConfig.isErr || taConfig.value.sub != selectedAnchor ||
                    !taConfig.value.exp.isFinite() || taConfig.value.exp <= Clock.System.now().epochSeconds
                ) {
                    return cacheAndReturn(cacheKey, TrustValidationResult(
                        trusted = false,
                        status = TrustStatus.UNTRUSTED,
                        validationPath = trustChain,
                        details = "Could not resolve the selected trust anchor configuration",
                        validatedAt = Clock.System.now()
                    ))
                }
                evidenceExpiry = minOf(
                    evidenceExpiry,
                    subjectConfig.value.exp.toLong(),
                    taConfig.value.exp.toLong(),
                )
                for (requiredType in requiredTrustMarks) {
                    var accepted = false
                    for (advertised in subjectConfig.value.trustMarks.orEmpty()) {
                        if (advertised.trustMarkType != requiredType) continue
                        val payload = runCatching { decodeJWTComponents(advertised.trustMark).payload }.getOrNull()
                            ?: continue
                        if (payload["trust_mark_type"]?.jsonPrimitive?.contentOrNull != requiredType ||
                            payload["sub"]?.jsonPrimitive?.contentOrNull != entityIdentifier
                        ) continue
                        val markExpiryClaim = payload["exp"]
                        val markExpiry = if (markExpiryClaim == null) null else signedExpiry(payload)
                        if (markExpiryClaim != null && (markExpiry == null || markExpiry <= Clock.System.now().epochSeconds)) continue
                        val markResult = verifyTrustMarkCommand.verifyTrustMark(
                            trustMark = advertised.trustMark,
                            trustAnchorConfig = taConfig.value,
                            subject = entityIdentifier
                        )
                        if (markResult.isErr || !markResult.value.isValid) continue
                        if (markExpiry != null) evidenceExpiry = minOf(evidenceExpiry, markExpiry)
                        accepted = true
                        break
                    }
                    if (!accepted) {
                        return cacheAndReturn(cacheKey, TrustValidationResult(
                            trusted = false,
                            status = TrustStatus.UNTRUSTED,
                            validationPath = trustChain,
                            details = "Required trust mark failed: $requiredType",
                            validatedAt = Clock.System.now()
                        ))
                    }
                }
            }

            if (evidenceExpiry <= Clock.System.now().epochSeconds) {
                return cacheAndReturn(cacheKey, TrustValidationResult(
                    trusted = false,
                    status = TrustStatus.UNTRUSTED,
                    validationPath = trustChain,
                    details = "Signed trust evidence expired during validation",
                    validatedAt = Clock.System.now()
                ))
            }

            // All checks passed
            val result = TrustValidationResult(
                trusted = true,
                status = TrustStatus.TRUSTED,
                validationPath = trustChain,
                trustChain = TrustChain.fromEntityStatementEntries(trustChain, TrustChainLinks.VERIFIED),
                details = "Entity trusted via OpenID Federation trust chain (${trustChain.size} links) at $selectedAnchor",
                validatedAt = Clock.System.now(),
                expiresAt = Instant.fromEpochSeconds(evidenceExpiry)
            )
            cacheAndReturn(cacheKey, enrichWithEntityInfo(result, request, entityInfoExtractor))
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

    override suspend fun doGetTrustAnchors(): List<TrustAnchor> {
        // TrustAnchor.keyInfo is @Contextual and not reliably JSON-serializable for CacheManager,
        // so resolve live from entity configurations rather than caching typed anchors.
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

        return anchors
    }

    private suspend fun cacheAndReturn(cacheKey: String, result: TrustValidationResult): TrustValidationResult {
        val expiry = result.expiresAt
        if (result.trusted && (expiry == null || expiry <= Clock.System.now())) {
            return result.copy(
                trusted = false,
                status = TrustStatus.UNTRUSTED,
                details = "Signed trust evidence expired during validation",
                validatedAt = Clock.System.now(),
            )
        }
        val remaining = expiry?.let { it - Clock.System.now() }
        if (remaining == null || remaining.isPositive()) {
            cache.putApp(cacheKey, result, remaining?.let { minOf(it, 30.minutes) })
        }
        if (result.trusted && expiry != null && expiry <= Clock.System.now()) {
            cache.removeApp(cacheKey)
            return result.copy(
                trusted = false,
                status = TrustStatus.UNTRUSTED,
                details = "Signed trust evidence expired during validation",
                validatedAt = Clock.System.now(),
            )
        }
        return result
    }

    private fun validationError(message: String) = TrustValidationResult(
        trusted = false,
        status = TrustStatus.VALIDATION_ERROR,
        details = message,
        validatedAt = Clock.System.now()
    )

    internal fun cacheKey(
        request: TrustValidationRequest,
        entityIdentifier: String,
        trustAnchors: List<String>,
        requiredTrustMarks: List<String>,
        maxChainDepth: Int,
    ): String =
        JsonArray(listOf(
            JsonPrimitive(entityIdentifier.trim()),
            JsonArray(trustAnchors.map { JsonPrimitive(it.trim()) }),
            JsonArray(requiredTrustMarks.map { JsonPrimitive(it.trim()) }),
            JsonPrimitive(maxChainDepth),
            JsonPrimitive(request.context.type),
            request.context.framework?.let { JsonPrimitive(it) } ?: JsonNull,
            JsonObject(request.context.parameters.toSortedMap().mapValues { JsonPrimitive(it.value) }),
            request.validationTime?.let { JsonPrimitive(it.toString()) } ?: JsonNull,
            JsonPrimitive(request.checkRevocation),
            request.entityDiscovery?.let { Json.encodeToJsonElement(EntityDiscoveryOptions.serializer(), it) } ?: JsonNull,
            JsonPrimitive(request.identifier.toString()),
        )).toString()

    internal fun selectedTrustAnchor(
        trustChain: List<String>,
        configuredAnchors: List<String>,
    ): String? {
        val configured = configuredAnchors.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (configured.isEmpty()) return null
        return runCatching {
            val terminal = decodeJWTComponents(trustChain.last()).payload
            val issuer = terminal["iss"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val subject = terminal["sub"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            issuer?.takeIf { subject != null && it in configured }
        }.getOrNull()
    }

    private fun signedExpiry(payload: JsonObject): Long? =
        runCatching {
            payload["exp"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 && it < Long.MAX_VALUE.toDouble() }
                ?.toLong()
        }.getOrNull()
}
