package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.MetadataPolicyNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.MetadataPolicy
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the DeleteMetadataPolicyCommand.
 * Deletes a metadata policy entry.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteMetadataPolicyCommand::class)
class DeleteMetadataPolicyCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<DeleteMetadataPolicyArgs, MetadataPolicy, FederationError>(
    id = DeleteMetadataPolicyCommand.COMMAND_ID,
    execution = execution
), DeleteMetadataPolicyCommand {

    private val logger = Log.app().withTag("DeleteMetadataPolicyCommand")
    private val metadataPolicyQueries = Persistence.metadataPolicyQueries

    override suspend fun deletePolicy(account: Account, id: String): IdkResult<MetadataPolicy, FederationError> {
        return execute(DeleteMetadataPolicyArgs(account, id), execution.sessionContext)
    }

    override suspend fun doExecute(
        args: DeleteMetadataPolicyArgs,
        sessionContext: SessionContext,
        applyDuring: (DeleteMetadataPolicyArgs) -> DeleteMetadataPolicyArgs
    ): IdkResult<MetadataPolicy, FederationError> {
        val (account, id) = applyDuring(args)

        logger.info("Deleting metadata policy ID: $id for account: ${account.username}")
        logger.debug("Using account with ID: ${account.id}")

        val policy = metadataPolicyQueries.findById(id).executeAsOneOrNull()

        if (policy == null) {
            logger.error("Metadata policy not found with ID: $id")
            return IdkResult.err(MetadataPolicyNotFoundError(id))
        }

        if (policy.account_id != account.id) {
            logger.error("Metadata policy ID: $id does not belong to account: ${account.username}")
            return IdkResult.err(MetadataPolicyNotFoundError(id))
        }

        return try {
            val deletedPolicy = metadataPolicyQueries.delete(id).executeAsOneOrNull()

            if (deletedPolicy != null) {
                logger.info("Successfully deleted metadata policy ID: $id")
                IdkResult.ok(deletedPolicy.toDTO())
            } else {
                logger.error("Failed to delete metadata policy ID: $id")
                IdkResult.err(ServerError(Constants.FAILED_TO_DELETE_ENTITY_CONFIGURATION_METADATA_POLICY))
            }
        } catch (e: Exception) {
            logger.error("Failed to delete metadata policy ID: $id for account: ${account.username}", e)
            IdkResult.err(ServerError("Failed to delete metadata policy", e.message, e))
        }
    }
}
