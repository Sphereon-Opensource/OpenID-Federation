package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.MetadataPolicyAlreadyExistsError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.MetadataPolicy
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the CreateMetadataPolicyCommand.
 * Creates a new metadata policy entry for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateMetadataPolicyCommand>())
class CreateMetadataPolicyCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<CreateMetadataPolicyArgs, MetadataPolicy, FederationError>(
    commandId = CreateMetadataPolicyCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<CreateMetadataPolicyArgs>(),
    outputTypeToken = typeToken<MetadataPolicy>()
), CreateMetadataPolicyCommand {

    private val logger = execution.federationLogger("CreateMetadataPolicyCommand")
    private val metadataPolicyQueries = Persistence.metadataPolicyQueries

    override suspend fun doExecute(
        args: CreateMetadataPolicyArgs,
        applyDuring: (CreateMetadataPolicyArgs) -> CreateMetadataPolicyArgs
    ): IdkResult<MetadataPolicy, FederationError> {
        val (tenantId, key, policy) = applyDuring(args)

        logger.info("Creating subordinate metadata policy for account: ${tenantId}, key: $key")
        logger.debug("Using account with ID: ${tenantId}")

        val policyAlreadyExists = metadataPolicyQueries
            .findByAccountIdAndKey(tenantId, key)
            .executeAsOneOrNull()

        if (policyAlreadyExists != null) {
            logger.error("Metadata policy already exists for account ID: ${tenantId}, key: $key")
            return federationErr(MetadataPolicyAlreadyExistsError(tenantId, key))
        }

        return try {
            val createdPolicy = metadataPolicyQueries
                .create(tenantId, key, policy.toString())
                .executeAsOneOrNull()

            if (createdPolicy != null) {
                logger.info("Successfully created metadata policy with ID: ${createdPolicy.id}")
                IdkResult.ok(createdPolicy.toDTO())
            } else {
                logger.error("Failed to create metadata policy for account ID: ${tenantId}, key: $key")
                federationErr(ServerError(Constants.FAILED_TO_CREATE_ENTITY_CONFIGURATION_METADATA_POLICY))
            }
        } catch (e: Exception) {
            logger.error("Failed to create metadata policy for account: ${tenantId}, key: $key", e)
            federationErr(ServerError("Failed to create metadata policy", e.message, e))
        }
    }
}
