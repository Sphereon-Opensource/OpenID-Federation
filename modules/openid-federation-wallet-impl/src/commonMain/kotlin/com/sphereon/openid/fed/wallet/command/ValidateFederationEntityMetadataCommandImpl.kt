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
import com.sphereon.openid.fed.wallet.policy.MetadataValidationCheck
import com.sphereon.openid.fed.wallet.policy.WalletProfileValidator
import kotlinx.serialization.json.jsonObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ValidateFederationEntityMetadataCommand>())
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
        currentTime: Long?,
        profileMode: MetadataProfileMode,
    ): IdkResult<FederationEntityMetadataValidationResult, FederationError> {
        return execute(
            ValidateFederationEntityMetadataArgs(
                entityIdentifier, trustAnchors, entityType, currentTime, profileMode
            )
        )
    }

    override suspend fun doExecute(
        args: ValidateFederationEntityMetadataArgs,
        applyDuring: (ValidateFederationEntityMetadataArgs) -> ValidateFederationEntityMetadataArgs
    ): IdkResult<FederationEntityMetadataValidationResult, FederationError> {
        val (entityIdentifier, trustAnchors, entityType, currentTime, profileMode) = applyDuring(args)

        logger.debug(
            "Validating federation metadata for entity: $entityIdentifier " +
                "(type=$entityType, profile=$profileMode)"
        )

        return try {
            val trustResult = evaluateEntityTrustCommand.evaluateEntityTrust(
                entityIdentifier = entityIdentifier,
                trustAnchors = trustAnchors,
                entityTypes = entityType?.let { arrayOf(it) },
                currentTime = currentTime
            )

            if (trustResult.isErr) {
                return IdkResult.err(trustResult.error)
            }

            val entityTrustResult = trustResult.value

            // Prefer Resolved Metadata from trust evaluation; fall back to leaf EC metadata
            val metadata = entityTrustResult.effectiveMetadata
                ?: run {
                    val entityConfigJwt = entityTrustResult.trustChain.firstOrNull()
                        ?: return IdkResult.err(
                            DiipProfileValidationError(
                                entityId = entityIdentifier,
                                failedChecks = listOf("No entity configuration in trust chain")
                            )
                        )
                    decodeJWTComponents(entityConfigJwt).payload["metadata"]?.jsonObject
                }

            if (metadata == null) {
                return IdkResult.err(
                    DiipProfileValidationError(
                        entityId = entityIdentifier,
                        failedChecks = listOf("No metadata in entity configuration")
                    )
                )
            }

            val validations = mutableListOf<MetadataValidationCheck>()

            if (profileMode == MetadataProfileMode.WALLET || profileMode == MetadataProfileMode.BOTH) {
                validations.addAll(
                    WalletProfileValidator.validate(
                        metadata = metadata,
                        entityIdentifier = entityIdentifier,
                        entityType = entityType,
                    )
                )
            }

            if (profileMode == MetadataProfileMode.DIIP || profileMode == MetadataProfileMode.BOTH) {
                validations.addAll(
                    DiipProfileValidator.validate(
                        metadata = metadata,
                        entityIdentifier = entityIdentifier,
                        entityType = entityType,
                    ).map { it.toMetadataCheck() }
                )
            }

            val failedChecks = validations.filter { !it.passed }
            val allPassed = failedChecks.isEmpty()

            if (!allPassed) {
                for (failed in failedChecks) {
                    logger.warn(
                        "Metadata validation failed [${failed.profile}]: ${failed.check} - ${failed.detail}"
                    )
                }
            }

            logger.debug(
                "Metadata validation ${if (allPassed) "passed" else "failed"} for $entityIdentifier " +
                    "(${validations.size} checks, profile=$profileMode)"
            )

            IdkResult.ok(
                FederationEntityMetadataValidationResult(
                    valid = allPassed,
                    entityIdentifier = entityIdentifier,
                    validations = validations,
                    entityTrustResult = entityTrustResult
                )
            )
        } catch (e: Exception) {
            logger.error("Metadata validation failed for $entityIdentifier", e)
            IdkResult.err(
                DiipProfileValidationError(
                    entityId = entityIdentifier,
                    failedChecks = listOf("Validation error: ${e.message}")
                )
            )
        }
    }
}
