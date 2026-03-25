package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.FederationClient
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.core.error.*
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.openapi.models.TrustMark
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<EvaluateEntityTrustCommand>())
class EvaluateEntityTrustCommandImpl(
    execution: SessionExecution,
    private val federationClient: FederationClient,
    private val applyMetadataPolicyCommand: ApplyMetadataPolicyCommand
) : ExecutionScopedCommandAdapter<EvaluateEntityTrustArgs, EntityTrustResult, FederationError>(
    id = EvaluateEntityTrustCommand.COMMAND_ID,
    execution = execution
), EvaluateEntityTrustCommand {

    private val logger = execution.federationLogger("EvaluateEntityTrustCommand")

    override suspend fun evaluateEntityTrust(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        entityTypes: Array<String>?,
        requiredTrustMarks: Array<String>?,
        currentTime: Long?
    ): IdkResult<EntityTrustResult, FederationError> {
        return execute(EvaluateEntityTrustArgs(entityIdentifier, trustAnchors, entityTypes, requiredTrustMarks, currentTime))
    }

    override suspend fun doExecute(
        args: EvaluateEntityTrustArgs,
        applyDuring: (EvaluateEntityTrustArgs) -> EvaluateEntityTrustArgs
    ): IdkResult<EntityTrustResult, FederationError> {
        val (entityIdentifier, trustAnchors, entityTypes, requiredTrustMarks, currentTime) = applyDuring(args)

        logger.info("Evaluating entity trust for: $entityIdentifier")

        return try {
            // 1. Resolve trust chain
            logger.debug("Resolving trust chain for $entityIdentifier")
            val trustChainResult = federationClient.trustChainResolve(entityIdentifier, trustAnchors)
            if (trustChainResult.isErr) {
                logger.error("Trust chain resolution failed for $entityIdentifier")
                return IdkResult.err(EntityNotTrustedError(
                    entityId = entityIdentifier,
                    reason = "Trust chain resolution failed: ${trustChainResult.error.message.defaultMessage}"
                ))
            }
            val trustChainResponse = trustChainResult.value
            val trustChain = trustChainResponse.trustChain

            if (trustChain.isEmpty()) {
                return IdkResult.err(EntityNotTrustedError(
                    entityId = entityIdentifier,
                    reason = "Empty trust chain"
                ))
            }

            // 2. Verify trust chain cryptographically
            logger.debug("Verifying trust chain for $entityIdentifier")
            val verifyResult = federationClient.trustChainVerify(
                trustChain = trustChain.toTypedArray(),
                trustAnchor = null,
                currentTime = currentTime
            )
            if (verifyResult.isErr) {
                logger.error("Trust chain verification failed for $entityIdentifier")
                return IdkResult.err(EntityNotTrustedError(
                    entityId = entityIdentifier,
                    reason = "Trust chain verification failed: ${verifyResult.error.message.defaultMessage}"
                ))
            }

            // 3. Apply metadata policies
            logger.debug("Applying metadata policies for $entityIdentifier")
            val policyResult = applyMetadataPolicyCommand.applyMetadataPolicy(
                trustChain = trustChain.toTypedArray(),
                entityType = entityTypes?.firstOrNull()
            )
            val effectiveMetadata = if (policyResult.isOk) policyResult.value.metadata else null

            // 4. Check entity types if specified
            if (entityTypes != null && effectiveMetadata != null) {
                val hasMatchingType = entityTypes.any { type ->
                    effectiveMetadata.containsKey(type)
                }
                if (!hasMatchingType) {
                    return IdkResult.err(EntityNotTrustedError(
                        entityId = entityIdentifier,
                        reason = "Entity does not have any of the required entity types: ${entityTypes.joinToString(", ")}"
                    ))
                }
            }

            // 5. Get entity configuration for trust mark verification
            logger.debug("Fetching entity configuration for trust mark verification")
            val entityConfigResult = federationClient.entityConfigurationStatementGet(entityIdentifier)
            val entityTrustMarks = if (entityConfigResult.isOk) {
                entityConfigResult.value.trustMarks ?: emptyList()
            } else {
                emptyList()
            }

            // 6. Verify trust marks
            val verifiedTrustMarks = mutableListOf<TrustMark>()
            if (entityTrustMarks.isNotEmpty() && entityConfigResult.isOk) {
                // Get trust anchor config for trust mark validation context
                val trustAnchorIdentifier = determineTrustAnchor(trustChain)
                val taConfigResult = federationClient.entityConfigurationStatementGet(trustAnchorIdentifier)

                if (taConfigResult.isOk) {
                    for (trustMark in entityTrustMarks) {
                        val tmResult = federationClient.trustMarksVerify(
                            trustMark = trustMark.trustMark,
                            trustAnchorConfig = taConfigResult.value,
                            currentTime = currentTime
                        )
                        if (tmResult.isOk) {
                            verifiedTrustMarks.add(trustMark)
                            logger.debug("Trust mark ${trustMark.trustMarkType} verified successfully")
                        } else {
                            logger.debug("Trust mark ${trustMark.trustMarkType} verification failed: ${tmResult.error.message.defaultMessage}")
                        }
                    }
                }
            }

            // 7. Check required trust marks
            if (requiredTrustMarks != null && requiredTrustMarks.isNotEmpty()) {
                val verifiedTrustMarkIds = verifiedTrustMarks.map { it.trustMarkType }.toSet()
                val missing = requiredTrustMarks.filter { it !in verifiedTrustMarkIds }
                if (missing.isNotEmpty()) {
                    return IdkResult.err(RequiredTrustMarkMissingError(
                        entityId = entityIdentifier,
                        missingTrustMarkIds = missing
                    ))
                }
            }

            val trustAnchor = determineTrustAnchor(trustChain)
            logger.info("Entity $entityIdentifier is trusted via trust anchor $trustAnchor")

            IdkResult.ok(EntityTrustResult(
                trusted = true,
                entityIdentifier = entityIdentifier,
                trustChain = trustChain,
                effectiveMetadata = effectiveMetadata,
                verifiedTrustMarks = verifiedTrustMarks,
                trustAnchor = trustAnchor
            ))
        } catch (e: Exception) {
            logger.error("Failed to evaluate entity trust for $entityIdentifier", e)
            IdkResult.err(EntityNotTrustedError(
                entityId = entityIdentifier,
                reason = "Trust evaluation failed: ${e.message}",
                exception = e
            ))
        }
    }

    /**
     * Determines the trust anchor from a trust chain.
     * The trust anchor is the last entity in the chain.
     */
    private fun determineTrustAnchor(trustChain: List<String>): String {
        val lastJwt = trustChain.last()
        return try {
            val decoded = decodeJWTComponents(lastJwt)
            decoded.payload["iss"]?.toString()?.trim('"') ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
