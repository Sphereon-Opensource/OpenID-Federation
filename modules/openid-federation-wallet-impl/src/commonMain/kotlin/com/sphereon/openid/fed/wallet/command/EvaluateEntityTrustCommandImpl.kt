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
import com.sphereon.openid.fed.wallet.policy.MetadataPolicyOperators
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn
import com.sphereon.openid.fed.core.error.TrustMarkNotRecognizedError

/**
 * Evaluates whether an entity is trusted in the federation (wallet architecture).
 *
 * Resolves and verifies a Trust Chain, derives full Resolved Metadata (entity-type keyed),
 * checks required Entity Types against that full metadata, and validates Trust Marks.
 */
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
            val trustChain = trustChainResult.value.trustChain

            if (trustChain.isEmpty()) {
                return IdkResult.err(EntityNotTrustedError(
                    entityId = entityIdentifier,
                    reason = "Empty trust chain"
                ))
            }

            val trustChainArray = trustChain.toTypedArray()
            val trustAnchor = determineTrustAnchor(trustChain)

            // 2. Verify trust chain cryptographically
            logger.debug("Verifying trust chain for $entityIdentifier against $trustAnchor")
            val verifyResult = federationClient.trustChainVerify(
                trustChain = trustChainArray,
                trustAnchor = trustAnchor.takeIf { it != "unknown" },
                currentTime = currentTime
            )
            if (verifyResult.isErr) {
                logger.error("Trust chain verification failed for $entityIdentifier")
                return IdkResult.err(EntityNotTrustedError(
                    entityId = entityIdentifier,
                    reason = "Trust chain verification failed: ${verifyResult.error.message.defaultMessage}"
                ))
            }
            if (!verifyResult.value.isValid) {
                return IdkResult.err(EntityNotTrustedError(
                    entityId = entityIdentifier,
                    reason = verifyResult.value.errorMessage
                        ?: "Trust chain verification returned invalid"
                ))
            }

            // 3. Apply metadata policies for full Resolved Metadata (entity-type keyed).
            // Must use entityType = null so the result is { "openid_wallet_provider": {...}, ... }
            // and not a scoped type-internal object (which broke containsKey(type) checks).
            logger.debug("Applying metadata policies for $entityIdentifier")
            val policyResult = applyMetadataPolicyCommand.applyMetadataPolicy(
                trustChain = trustChainArray,
                entityType = null
            )
            if (policyResult.isErr) {
                logger.error("Metadata policy application failed for $entityIdentifier")
                return IdkResult.err(EntityNotTrustedError(
                    entityId = entityIdentifier,
                    reason = "Metadata policy application failed: ${policyResult.error.message.defaultMessage}"
                ))
            }
            val fullResolvedMetadata = policyResult.value.metadata

            // 4. Check required entity types against full Resolved Metadata keys
            if (entityTypes != null && entityTypes.isNotEmpty()) {
                val presentTypes = entityTypes.filter { fullResolvedMetadata.containsKey(it) }
                if (presentTypes.isEmpty()) {
                    val available = fullResolvedMetadata.keys.joinToString(", ").ifEmpty { "(none)" }
                    return IdkResult.err(EntityNotTrustedError(
                        entityId = entityIdentifier,
                        reason = "Entity does not have any of the required entity types: " +
                            "${entityTypes.joinToString(", ")} (available: $available)"
                    ))
                }
                logger.debug("Required entity types present: ${presentTypes.joinToString(", ")}")
            }

            // Return full resolved metadata, optionally filtered to requested types only
            val effectiveMetadata = MetadataPolicyOperators.filterEntityTypes(
                metadata = fullResolvedMetadata,
                entityTypes = entityTypes?.toList()
            )

            // 5. Get entity configuration for trust mark verification
            logger.debug("Fetching entity configuration for trust mark verification")
            val entityConfigResult = federationClient.entityConfigurationStatementGet(entityIdentifier)
            val entityTrustMarks = if (entityConfigResult.isOk) {
                entityConfigResult.value.trustMarks ?: emptyList()
            } else {
                emptyList()
            }

            // 6. Verify trust marks against THIS federation's Trust Anchor only.
            // Cross-federation marks (not recognized by this TA) are filtered out — they do not
            // fail the entity and are not included in verifiedTrustMarks.
            val verifiedTrustMarks = mutableListOf<TrustMark>()
            if (entityTrustMarks.isNotEmpty()) {
                val taConfigResult = federationClient.entityConfigurationStatementGet(trustAnchor)

                if (taConfigResult.isOk) {
                    for (trustMark in entityTrustMarks) {
                        val tmResult = federationClient.trustMarksVerify(
                            trustMark = trustMark.trustMark,
                            trustAnchorConfig = taConfigResult.value,
                            currentTime = currentTime,
                            subject = entityIdentifier
                        )
                        when {
                            tmResult.isOk -> {
                                verifiedTrustMarks.add(trustMark)
                                logger.debug(
                                    "Trust mark ${trustMark.trustMarkType} verified for federation TA $trustAnchor"
                                )
                            }
                            tmResult.error is TrustMarkNotRecognizedError -> {
                                logger.debug(
                                    "Trust mark ${trustMark.trustMarkType} not recognized by " +
                                        "federation TA $trustAnchor — filtered out (cross-federation mark)"
                                )
                            }
                            else -> {
                                logger.debug(
                                    "Trust mark ${trustMark.trustMarkType} invalid under TA $trustAnchor: " +
                                        tmResult.error.message.defaultMessage
                                )
                            }
                        }
                    }
                } else {
                    logger.warn(
                        "Could not fetch Trust Anchor config for trust mark verification: " +
                            taConfigResult.error.message.defaultMessage
                    )
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
     * Trust Anchor is the issuer of the last statement in the chain (TA Entity Configuration).
     */
    private fun determineTrustAnchor(trustChain: List<String>): String {
        val lastJwt = trustChain.last()
        return try {
            val decoded = decodeJWTComponents(lastJwt)
            decoded.payload["iss"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
