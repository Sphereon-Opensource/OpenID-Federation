package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.FederationClient
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.core.error.MetadataPolicyApplicationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.TenantNotFoundError
import com.sphereon.openid.fed.core.error.TrustChainValidationFailedError
import com.sphereon.openid.fed.core.error.TrustMarkNotRecognizedError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.ResolveResponse
import com.sphereon.openid.fed.openapi.models.TrustMark
import com.sphereon.openid.fed.wallet.policy.MetadataPolicyOperators
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn
import kotlin.math.min

/**
 * Implementation of the ResolveEntityCommand (OIDFed 1.1 §8.3).
 *
 * Produces Resolved Metadata by verifying the Trust Chain and applying metadata policies
 * (§6.1 / §10), plus verified Trust Marks when possible.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ResolveEntityCommand>())
class ResolveEntityCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val federationClient: FederationClient
) : TypedServiceCommandAdapter<ResolveEntityArgs, ResolveResponse, FederationError>(
    commandId = ResolveEntityCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<ResolveEntityArgs>(),
    outputTypeToken = typeToken<ResolveResponse>()
), ResolveEntityCommand {

    private val logger = execution.federationLogger("ResolveEntityCommand")
    private val ONE_DAY_IN_SEC = 3600 * 24

    override suspend fun doExecute(
        args: ResolveEntityArgs,
        applyDuring: (ResolveEntityArgs) -> ResolveEntityArgs
    ): IdkResult<ResolveResponse, FederationError> {
        val (tenantId, sub, trustAnchors, entityTypes) = applyDuring(args)

        logger.info(
            "Resolving entity for subject: $sub, trust anchors: ${trustAnchors.joinToString()}"
        )

        return try {
            if (trustAnchors.isEmpty()) {
                return federationErr(
                    TrustChainValidationFailedError(
                        entityId = sub,
                        reason = "At least one trust_anchor is required",
                    )
                )
            }
            logger.debug("Using tenant: $tenantId")
            logger.debug("Entity types filter: ${entityTypes?.joinToString(", ") ?: "none"}")

            // 1. Resolve Trust Chain (bottom-up discovery) — multi-TA preference order
            logger.debug(
                "Resolving trust chain from $sub to trust anchors: ${trustAnchors.joinToString()}"
            )
            val trustChainResult = federationClient.trustChainResolve(sub, trustAnchors)
            if (trustChainResult.isErr) {
                logger.error("Trust chain resolution failed for entity: $sub")
                return federationErr(trustChainResult.error)
            }
            val trustChain = trustChainResult.value.trustChain
            if (trustChain.isEmpty()) {
                return federationErr(TrustChainValidationFailedError(
                    entityId = sub,
                    reason = "Empty trust chain"
                ))
            }
            val trustChainArray = trustChain.toTypedArray()
            // Selected TA: last EC iss/sub on the chain if present, else first requested TA
            val selectedTrustAnchor = selectedTrustAnchorFromChain(trustChain, trustAnchors)
            logger.debug(
                "Trust chain resolution completed (${trustChain.size} statements, ta=$selectedTrustAnchor)"
            )

            // 2. Verify Trust Chain cryptographically (§10.2)
            logger.debug("Verifying trust chain for subject: $sub")
            val verifyResult = federationClient.trustChainVerify(
                trustChain = trustChainArray,
                trustAnchor = selectedTrustAnchor,
                currentTime = System.currentTimeMillis() / 1000
            )
            if (verifyResult.isErr) {
                logger.error("Trust chain verification failed for entity: $sub")
                return federationErr(verifyResult.error)
            }
            if (!verifyResult.value.isValid) {
                return federationErr(TrustChainValidationFailedError(
                    entityId = sub,
                    reason = verifyResult.value.errorMessage ?: "Trust chain verification returned invalid"
                ))
            }

            // 3. Derive Resolved Metadata (§6.1 / §10)
            logger.debug("Applying metadata policies for Resolved Metadata")
            val decodedStatements = trustChain.map { decodeJWTComponents(it).payload }
            val policyResult = MetadataPolicyOperators.resolveFromTrustChainPayloads(
                decodedStatements = decodedStatements,
                entityType = null // full metadata first; filter entity types after
            )
            if (!policyResult.isValid) {
                logger.error("Metadata policy application failed: ${policyResult.errors}")
                return federationErr(MetadataPolicyApplicationError(
                    entityId = sub,
                    reason = policyResult.errors.joinToString("; ")
                ))
            }
            for (warning in policyResult.warnings) {
                logger.warn(warning)
            }

            val resolvedMetadata = MetadataPolicyOperators.filterEntityTypes(
                metadata = policyResult.metadata,
                entityTypes = entityTypes?.toList()
            )
            logger.debug(
                "Resolved Metadata ready (policiesApplied=${policyResult.policiesApplied}, " +
                    "entityTypes=${entityTypes?.joinToString() ?: "all"})"
            )

            // 4. Verify Trust Marks using Trust Anchor configuration (§7.3 context)
            logger.debug("Verifying trust marks for subject: $sub")
            val leafConfigResult = federationClient.entityConfigurationStatementGet(sub)
            val trustMarks = if (leafConfigResult.isOk) {
                getVerifiedTrustMarks(leafConfigResult.value, selectedTrustAnchor)
            } else {
                logger.warn("Could not re-fetch leaf EC for trust marks: ${leafConfigResult.error.message.defaultMessage}")
                emptyArray()
            }

            val currentTime = System.currentTimeMillis() / 1000
            val chainExp = minTrustChainExp(decodedStatements)
            val exp = if (chainExp != null) {
                min(chainExp, currentTime + ONE_DAY_IN_SEC)
            } else {
                currentTime + ONE_DAY_IN_SEC
            }

            buildResolveResponse(
                currentTime = currentTime,
                exp = exp,
                tenantId = tenantId,
                sub = sub,
                metadata = resolvedMetadata,
                trustMarks = trustMarks,
                trustChain = trustChainArray
            )
        } catch (e: Exception) {
            logger.error("Failed to resolve entity for subject: $sub", e)
            federationErr(ServerError(
                reason = "Failed to resolve entity",
                causeDescription = e.message,
                exception = e
            ))
        }
    }

    private suspend fun buildResolveResponse(
        currentTime: Long,
        exp: Long,
        tenantId: String,
        sub: String,
        metadata: JsonObject,
        trustMarks: Array<TrustMark>,
        trustChain: Array<String>
    ): IdkResult<ResolveResponse, FederationError> {
        val iss = tenantContextResolver.resolveIdentifier(tenantId)
            ?: return federationErr(TenantNotFoundError(tenantId))

        return IdkResult.ok(
            ResolveResponse(
                iss = iss,
                sub = sub,
                iat = currentTime.toDouble(),
                exp = exp.toDouble(),
                metadata = metadata,
                trustMarks = trustMarks.toList(),
                trustChain = trustChain.toList()
            )
        )
    }

    /**
     * Earliest `exp` among all statements in the chain (Trust Chain lifetime).
     */
    private fun minTrustChainExp(decodedStatements: List<JsonObject>): Long? {
        var minExp: Long? = null
        for (stmt in decodedStatements) {
            val exp = stmt["exp"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: continue
            minExp = if (minExp == null) exp else min(minExp, exp)
        }
        return minExp
    }

    /**
     * Prefer the last statement's `iss` when it is one of the requested Trust Anchors
     * (TA Entity Configuration). Fall back to the first requested TA.
     */
    private fun selectedTrustAnchorFromChain(
        trustChain: List<String>,
        requested: Array<String>,
    ): String {
        if (trustChain.isNotEmpty()) {
            try {
                val last = decodeJWTComponents(trustChain.last()).payload
                val iss = last["iss"]?.jsonPrimitive?.contentOrNull
                val sub = last["sub"]?.jsonPrimitive?.contentOrNull
                if (iss != null && iss == sub && iss in requested) return iss
                // Intermediate topology: SS about intermediate from TA — TA is iss of last SS
                if (iss != null && iss in requested) return iss
            } catch (_: Exception) {
                // fall through
            }
        }
        return requested.first()
    }

    /**
     * Verify Trust Marks from the subject's Entity Configuration under the **requested**
     * Trust Anchor only (OIDFed 1.1 §3.1.2 / §7.3).
     *
     * Cross-federation: marks not recognized by this TA are filtered out of the resolve
     * response (they may still be valid in another federation that trusts their issuer).
     * Invalid recognized marks are also omitted; they do not fail the whole resolve.
     */
    private suspend fun getVerifiedTrustMarks(
        subEntityConfigurationStatement: EntityConfigurationStatement,
        trustAnchor: String
    ): Array<TrustMark> {
        try {
            val trustMarks = subEntityConfigurationStatement.trustMarks ?: return arrayOf()
            val verifiedTrustMarks = mutableListOf<TrustMark>()
            val subject = subEntityConfigurationStatement.sub

            val taConfigResult = federationClient.entityConfigurationStatementGet(trustAnchor)
            if (taConfigResult.isErr) {
                logger.warn(
                    "Failed to fetch Trust Anchor config for trust mark verification: " +
                        taConfigResult.error.message.defaultMessage
                )
                return arrayOf()
            }
            val trustAnchorConfig = taConfigResult.value

            for (trustMark in trustMarks) {
                try {
                    val validationResult = federationClient.trustMarksVerify(
                        trustMark = trustMark.trustMark,
                        trustAnchorConfig = trustAnchorConfig,
                        subject = subject
                    )

                    when {
                        validationResult.isOk -> {
                            verifiedTrustMarks.add(trustMark)
                            logger.debug(
                                "Trust mark ${trustMark.trustMarkType} verified for federation TA $trustAnchor"
                            )
                        }
                        validationResult.error is TrustMarkNotRecognizedError -> {
                            logger.debug(
                                "Trust mark ${trustMark.trustMarkType} not recognized by " +
                                    "federation TA $trustAnchor — filtered out of resolve response " +
                                    "(cross-federation mark)"
                            )
                        }
                        else -> {
                            logger.warn(
                                "Trust mark ${trustMark.trustMarkType} invalid under TA $trustAnchor: " +
                                    validationResult.error.message.defaultMessage
                            )
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to verify trust mark ${trustMark.trustMarkType}: ${e.message}")
                }
            }

            return if (verifiedTrustMarks.isEmpty()) arrayOf() else verifiedTrustMarks.toTypedArray()
        } catch (e: Exception) {
            logger.error("Error verifying trust marks: ${e.message}")
            return arrayOf()
        }
    }
}
