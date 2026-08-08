package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.EndpointConstraintViolationError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.wallet.policy.EndpointConstraintValidator
import kotlinx.serialization.json.JsonObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ValidateEndpointConstraintsCommand>())
class ValidateEndpointConstraintsCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<ValidateEndpointConstraintsArgs, EndpointConstraintsResult, FederationError>(
    id = ValidateEndpointConstraintsCommand.COMMAND_ID,
    execution = execution
), ValidateEndpointConstraintsCommand {

    private val logger = execution.federationLogger("ValidateEndpointConstraintsCommand")

    override suspend fun validateEndpointConstraints(
        entityMetadata: JsonObject,
        requestUri: String?,
        responseUri: String?,
        redirectUri: String?,
        entityType: String
    ): IdkResult<EndpointConstraintsResult, FederationError> {
        return execute(ValidateEndpointConstraintsArgs(entityMetadata, requestUri, responseUri, redirectUri, entityType))
    }

    override suspend fun doExecute(
        args: ValidateEndpointConstraintsArgs,
        applyDuring: (ValidateEndpointConstraintsArgs) -> ValidateEndpointConstraintsArgs
    ): IdkResult<EndpointConstraintsResult, FederationError> {
        val (entityMetadata, requestUri, responseUri, redirectUri, entityType) = applyDuring(args)

        logger.debug("Validating endpoint constraints for entity type: $entityType")

        // Get the entity type metadata object
        val typeMetadata = entityMetadata[entityType] as? JsonObject ?: entityMetadata

        val validations = EndpointConstraintValidator.validate(
            metadata = typeMetadata,
            requestUri = requestUri,
            responseUri = responseUri,
            redirectUri = redirectUri
        )

        // Check for failures
        for (validation in validations) {
            if (!validation.valid) {
                logger.error("${validation.endpointType} '${validation.value}' is not pre-registered in entity metadata")
                return IdkResult.err(EndpointConstraintViolationError(
                    entityId = "unknown",
                    endpointType = validation.endpointType,
                    actualValue = validation.value,
                    reason = "not pre-registered in entity metadata. Registered: ${validation.registeredValues.joinToString(", ")}"
                ))
            }
        }

        val validatedEndpoints = validations.associate { it.endpointType to it.valid }

        logger.debug("All endpoint constraints validated successfully")
        return IdkResult.ok(EndpointConstraintsResult(
            valid = true,
            validatedEndpoints = validatedEndpoints
        ))
    }
}
