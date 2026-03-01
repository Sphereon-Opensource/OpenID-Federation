package com.sphereon.openid.fed.services.command.metadata

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.MetadataNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.Metadata
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the DeleteMetadataCommand.
 * Deletes a metadata entry.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteMetadataCommand::class)
class DeleteMetadataCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<DeleteMetadataArgs, Metadata>(
    commandId = DeleteMetadataCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<DeleteMetadataArgs>(),
    outputTypeToken = typeToken<Metadata>()
), DeleteMetadataCommand {

    private val logger = Log.app().withTag("DeleteMetadataCommand")
    private val metadataQueries = Persistence.metadataQueries

    override suspend fun doExecute(
        args: DeleteMetadataArgs,
        applyDuring: (DeleteMetadataArgs) -> DeleteMetadataArgs
    ): IdkResult<Metadata, IdkError> {
        val (tenantId, id) = applyDuring(args)

        logger.info("Deleting metadata ID: $id for account: ${tenantId}")
        logger.debug("Using account with ID: ${tenantId}")

        val metadata = metadataQueries.findById(id).executeAsOneOrNull()

        if (metadata == null) {
            logger.error("Metadata not found with ID: $id")
            return federationErr(MetadataNotFoundError(id))
        }

        if (metadata.account_id != tenantId) {
            logger.error("Metadata ID: $id does not belong to account: ${tenantId}")
            return federationErr(MetadataNotFoundError(id))
        }

        return try {
            val deletedMetadata = metadataQueries.delete(id).executeAsOneOrNull()

            if (deletedMetadata != null) {
                logger.info("Successfully deleted metadata ID: $id")
                IdkResult.ok(deletedMetadata.toDTO())
            } else {
                logger.error("Failed to delete metadata ID: $id")
                federationErr(ServerError(Constants.FAILED_TO_DELETE_ENTITY_CONFIGURATION_METADATA))
            }
        } catch (e: Exception) {
            logger.error("Failed to delete metadata ID: $id for account: ${tenantId}", e)
            federationErr(ServerError("Failed to delete metadata", e.message, e))
        }
    }
}
