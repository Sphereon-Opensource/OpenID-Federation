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
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Applies OpenID Federation 1.1 metadata policies from a Trust Chain.
 * Delegates to [MetadataPolicyOperators.resolveFromTrustChainPayloads].
 */
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
            val decodedStatements = trustChain.map { jwt ->
                decodeJWTComponents(jwt).payload
            }
            val entityId = decodedStatements.first()["sub"]
                ?.toString()
                ?.trim('"')
                ?: "unknown"

            val result = MetadataPolicyOperators.resolveFromTrustChainPayloads(
                decodedStatements = decodedStatements,
                entityType = entityType
            )

            if (!result.isValid) {
                return IdkResult.err(MetadataPolicyApplicationError(
                    entityId = entityId,
                    reason = result.errors.joinToString("; ")
                ))
            }

            for (warning in result.warnings) {
                logger.warn(warning)
            }

            logger.debug("Applied ${result.policiesApplied} metadata policies")

            IdkResult.ok(EffectiveMetadataResult(
                metadata = result.metadata,
                entityType = entityType,
                policiesApplied = result.policiesApplied
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
