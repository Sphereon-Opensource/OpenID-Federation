package com.sphereon.openid.fed.services.command.metadata

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.MetadataNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
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
) : ExecutionScopedCommandAdapter<DeleteMetadataArgs, Metadata, FederationError>(
    id = DeleteMetadataCommand.COMMAND_ID,
    execution = execution
), DeleteMetadataCommand {

    private val logger = Log.app().withTag("DeleteMetadataCommand")
    private val metadataQueries = Persistence.metadataQueries

    override suspend fun deleteMetadata(account: Account, id: String): IdkResult<Metadata, FederationError> {
        return execute(DeleteMetadataArgs(account, id))
    }

    override suspend fun doExecute(
        args: DeleteMetadataArgs,
        applyDuring: (DeleteMetadataArgs) -> DeleteMetadataArgs
    ): IdkResult<Metadata, FederationError> {
        val (account, id) = applyDuring(args)

        logger.info("Deleting metadata ID: $id for account: ${account.username}")
        logger.debug("Using account with ID: ${account.id}")

        val metadata = metadataQueries.findById(id).executeAsOneOrNull()

        if (metadata == null) {
            logger.error("Metadata not found with ID: $id")
            return IdkResult.err(MetadataNotFoundError(id))
        }

        if (metadata.account_id != account.id) {
            logger.error("Metadata ID: $id does not belong to account: ${account.username}")
            return IdkResult.err(MetadataNotFoundError(id))
        }

        return try {
            val deletedMetadata = metadataQueries.delete(id).executeAsOneOrNull()

            if (deletedMetadata != null) {
                logger.info("Successfully deleted metadata ID: $id")
                IdkResult.ok(deletedMetadata.toDTO())
            } else {
                logger.error("Failed to delete metadata ID: $id")
                IdkResult.err(ServerError(Constants.FAILED_TO_DELETE_ENTITY_CONFIGURATION_METADATA))
            }
        } catch (e: Exception) {
            logger.error("Failed to delete metadata ID: $id for account: ${account.username}", e)
            IdkResult.err(ServerError("Failed to delete metadata", e.message, e))
        }
    }
}
