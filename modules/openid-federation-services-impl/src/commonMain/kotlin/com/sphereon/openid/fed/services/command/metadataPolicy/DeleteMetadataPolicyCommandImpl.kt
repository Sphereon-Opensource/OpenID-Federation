package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.MetadataPolicyNotFoundError
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
 * Implementation of the DeleteMetadataPolicyCommand.
 * Deletes a metadata policy entry.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteMetadataPolicyCommand>())
class DeleteMetadataPolicyCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<DeleteMetadataPolicyArgs, MetadataPolicy>(
    commandId = DeleteMetadataPolicyCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<DeleteMetadataPolicyArgs>(),
    outputTypeToken = typeToken<MetadataPolicy>()
), DeleteMetadataPolicyCommand {

    private val logger = execution.federationLogger("DeleteMetadataPolicyCommand")
    private val metadataPolicyQueries = Persistence.metadataPolicyQueries

    override suspend fun doExecute(
        args: DeleteMetadataPolicyArgs,
        applyDuring: (DeleteMetadataPolicyArgs) -> DeleteMetadataPolicyArgs
    ): IdkResult<MetadataPolicy, IdkError> {
        val (tenantId, id) = applyDuring(args)

        logger.info("Deleting metadata policy ID: $id for account: ${tenantId}")
        logger.debug("Using account with ID: ${tenantId}")

        val policy = metadataPolicyQueries.findById(id).executeAsOneOrNull()

        if (policy == null) {
            logger.error("Metadata policy not found with ID: $id")
            return federationErr(MetadataPolicyNotFoundError(id))
        }

        if (policy.account_id != tenantId) {
            logger.error("Metadata policy ID: $id does not belong to account: ${tenantId}")
            return federationErr(MetadataPolicyNotFoundError(id))
        }

        return try {
            val deletedPolicy = metadataPolicyQueries.delete(id).executeAsOneOrNull()

            if (deletedPolicy != null) {
                logger.info("Successfully deleted metadata policy ID: $id")
                IdkResult.ok(deletedPolicy.toDTO())
            } else {
                logger.error("Failed to delete metadata policy ID: $id")
                federationErr(ServerError(Constants.FAILED_TO_DELETE_ENTITY_CONFIGURATION_METADATA_POLICY))
            }
        } catch (e: Exception) {
            logger.error("Failed to delete metadata policy ID: $id for account: ${tenantId}", e)
            federationErr(ServerError("Failed to delete metadata policy", e.message, e))
        }
    }
}
