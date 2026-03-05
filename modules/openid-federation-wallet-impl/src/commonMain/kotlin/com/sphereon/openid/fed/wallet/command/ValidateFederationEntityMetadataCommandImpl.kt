package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.core.error.DiipProfileValidationError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.wallet.policy.DiipProfileValidator
import kotlinx.serialization.json.jsonObject
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ValidateFederationEntityMetadataCommand::class)
class ValidateFederationEntityMetadataCommandImpl(
    execution: SessionExecution,
    private val evaluateEntityTrustCommand: EvaluateEntityTrustCommand
) : ExecutionScopedCommandAdapter<ValidateFederationEntityMetadataArgs, FederationEntityMetadataValidationResult, FederationError>(
    id = ValidateFederationEntityMetadataCommand.COMMAND_ID,
    execution = execution
), ValidateFederationEntityMetadataCommand {

    private val logger = execution.federationLogger("ValidateFederationEntityMetadataCommand")

    override suspend fun validateFederationEntityMetadata(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        entityType: String?,
        currentTime: Long?
    ): IdkResult<FederationEntityMetadataValidationResult, FederationError> {
        return execute(ValidateFederationEntityMetadataArgs(entityIdentifier, trustAnchors, entityType, currentTime))
    }

    override suspend fun doExecute(
        args: ValidateFederationEntityMetadataArgs,
        applyDuring: (ValidateFederationEntityMetadataArgs) -> ValidateFederationEntityMetadataArgs
    ): IdkResult<FederationEntityMetadataValidationResult, FederationError> {
        val (entityIdentifier, trustAnchors, entityType, currentTime) = applyDuring(args)

        logger.debug("Validating DIIP metadata for entity: $entityIdentifier")

        return try {
            // 1. Evaluate entity trust first
            val trustResult = evaluateEntityTrustCommand.evaluateEntityTrust(
                entityIdentifier = entityIdentifier,
                trustAnchors = trustAnchors,
                currentTime = currentTime
            )

            if (trustResult.isErr) {
                return IdkResult.err(trustResult.error)
            }

            val entityTrustResult = trustResult.value

            // 2. Extract metadata from entity configuration (first JWT in trust chain)
            val entityConfigJwt = entityTrustResult.trustChain.firstOrNull()
            if (entityConfigJwt == null) {
                return IdkResult.err(DiipProfileValidationError(
                    entityId = entityIdentifier,
                    failedChecks = listOf("No entity configuration in trust chain")
                ))
            }

            val entityConfigPayload = decodeJWTComponents(entityConfigJwt).payload
            val metadata = entityConfigPayload["metadata"]?.jsonObject

            if (metadata == null) {
                return IdkResult.err(DiipProfileValidationError(
                    entityId = entityIdentifier,
                    failedChecks = listOf("No metadata in entity configuration")
                ))
            }

            // 3. Run DIIP profile validations
            val validations = DiipProfileValidator.validate(
                metadata = metadata,
                entityIdentifier = entityIdentifier,
                entityType = entityType
            )

            val failedChecks = validations.filter { !it.passed }
            val allPassed = failedChecks.isEmpty()

            if (!allPassed) {
                for (failed in failedChecks) {
                    logger.warn("DIIP validation failed: ${failed.check} - ${failed.detail}")
                }
            }

            logger.debug("DIIP metadata validation ${if (allPassed) "passed" else "failed"} for $entityIdentifier")

            IdkResult.ok(FederationEntityMetadataValidationResult(
                valid = allPassed,
                entityIdentifier = entityIdentifier,
                validations = validations,
                entityTrustResult = entityTrustResult
            ))
        } catch (e: Exception) {
            logger.error("DIIP metadata validation failed for $entityIdentifier", e)
            IdkResult.err(DiipProfileValidationError(
                entityId = entityIdentifier,
                failedChecks = listOf("Validation error: ${e.message}")
            ))
        }
    }
}
