package com.sphereon.openid.fed.services.command.metadata

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.MetadataAlreadyExistsError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.Metadata
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the CreateMetadataCommand.
 * Creates a new metadata entry for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateMetadataCommand>())
class CreateMetadataCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<CreateMetadataArgs, Metadata, FederationError>(
    commandId = CreateMetadataCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<CreateMetadataArgs>(),
    outputTypeToken = typeToken<Metadata>()
), CreateMetadataCommand {

    private val logger = execution.federationLogger("CreateMetadataCommand")
    private val metadataQueries = Persistence.metadataQueries

    override suspend fun doExecute(
        args: CreateMetadataArgs,
        applyDuring: (CreateMetadataArgs) -> CreateMetadataArgs
    ): IdkResult<Metadata, FederationError> {
        val (tenantId, key, metadata) = applyDuring(args)

        logger.info("Creating entity configuration metadata for account: ${tenantId}, key: $key")
        logger.debug("Using account with ID: ${tenantId}")

        val metadataAlreadyExists = metadataQueries
            .findByAccountIdAndKey(tenantId, key)
            .executeAsOneOrNull()

        if (metadataAlreadyExists != null) {
            logger.error("Metadata already exists for account ID: ${tenantId}, key: $key")
            return federationErr(MetadataAlreadyExistsError(tenantId, key))
        }

        return try {
            val createdMetadata = metadataQueries
                .create(tenantId, key, metadata.toString())
                .executeAsOneOrNull()

            if (createdMetadata != null) {
                logger.info("Successfully created metadata with ID: ${createdMetadata.id}")
                IdkResult.ok(createdMetadata.toDTO())
            } else {
                logger.error("Failed to create metadata for account ID: ${tenantId}, key: $key")
                federationErr(ServerError(Constants.FAILED_TO_CREATE_ENTITY_CONFIGURATION_METADATA))
            }
        } catch (e: Exception) {
            logger.error("Failed to create metadata for account: ${tenantId}, key: $key", e)
            federationErr(ServerError("Failed to create metadata", e.message, e))
        }
    }
}
