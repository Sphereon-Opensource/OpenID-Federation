package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.MetadataPolicyApplicationError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.wallet.policy.MetadataPolicyOperators
import kotlinx.serialization.json.*
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ApplyMetadataPolicyCommand>())
class ApplyMetadataPolicyCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<ApplyMetadataPolicyArgs, EffectiveMetadataResult, FederationError>(
    id = ApplyMetadataPolicyCommand.COMMAND_ID,
    execution = execution
), ApplyMetadataPolicyCommand {

    private val logger = execution.federationLogger("ApplyMetadataPolicyCommand")

    override suspend fun applyMetadataPolicy(
        trustChain: Array<String>,
        entityType: String?
    ): IdkResult<EffectiveMetadataResult, FederationError> {
        return execute(ApplyMetadataPolicyArgs(trustChain, entityType))
    }

    override suspend fun doExecute(
        args: ApplyMetadataPolicyArgs,
        applyDuring: (ApplyMetadataPolicyArgs) -> ApplyMetadataPolicyArgs
    ): IdkResult<EffectiveMetadataResult, FederationError> {
        val (trustChain, entityType) = applyDuring(args)

        logger.debug("Applying metadata policies from trust chain (${trustChain.size} statements)")

        if (trustChain.isEmpty()) {
            return IdkResult.err(MetadataPolicyApplicationError(
                entityId = "unknown",
                reason = "Trust chain is empty"
            ))
        }

        return try {
            // Decode all statements in the trust chain
            val decodedStatements = trustChain.map { jwt ->
                decodeJWTComponents(jwt).payload
            }

            // The first statement is the leaf entity's configuration
            val leafMetadata = decodedStatements.first()["metadata"]?.jsonObject
                ?: return IdkResult.ok(EffectiveMetadataResult(
                    metadata = JsonObject(emptyMap()),
                    entityType = entityType,
                    policiesApplied = 0
                ))

            // Collect metadata policies from intermediate entities and trust anchor
            // Walk from trust anchor (last) to leaf (first), collecting policies
            var policiesApplied = 0
            var combinedPolicy = JsonObject(emptyMap())

            for (i in decodedStatements.size - 1 downTo 1) {
                val statement = decodedStatements[i]
                val metadataPolicy = statement["metadata_policy"]?.jsonObject
                if (metadataPolicy != null) {
                    combinedPolicy = MetadataPolicyOperators.mergePolicies(combinedPolicy, metadataPolicy)
                    policiesApplied++
                }
            }

            // Apply the combined policy to the leaf metadata
            val effectiveMetadata = if (policiesApplied > 0) {
                val result = MetadataPolicyOperators.applyPolicy(leafMetadata, combinedPolicy, entityType)
                for (warning in result.warnings) {
                    logger.warn(warning)
                }
                result.metadata
            } else {
                if (entityType != null) {
                    leafMetadata[entityType]?.jsonObject ?: leafMetadata
                } else {
                    leafMetadata
                }
            }

            logger.debug("Applied $policiesApplied metadata policies")

            IdkResult.ok(EffectiveMetadataResult(
                metadata = effectiveMetadata,
                entityType = entityType,
                policiesApplied = policiesApplied
            ))
        } catch (e: Exception) {
            logger.error("Failed to apply metadata policies", e)
            IdkResult.err(MetadataPolicyApplicationError(
                entityId = "unknown",
                reason = "Failed to apply metadata policies: ${e.message}",
                exception = e
            ))
        }
    }
}
