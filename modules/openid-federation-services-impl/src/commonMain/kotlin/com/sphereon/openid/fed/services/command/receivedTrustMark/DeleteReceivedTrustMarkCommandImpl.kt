package com.sphereon.openid.fed.services.command.receivedTrustMark

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ReceivedTrustMarkNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the DeleteReceivedTrustMarkCommand.
 * Deletes a received trust mark.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteReceivedTrustMarkCommand>())
class DeleteReceivedTrustMarkCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<DeleteReceivedTrustMarkArgs, ReceivedTrustMark, FederationError>(
    commandId = DeleteReceivedTrustMarkCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<DeleteReceivedTrustMarkArgs>(),
    outputTypeToken = typeToken<ReceivedTrustMark>()
), DeleteReceivedTrustMarkCommand {

    private val logger = execution.federationLogger("DeleteReceivedTrustMarkCommand")
    private val receivedTrustMarkQueries = Persistence.receivedTrustMarkQueries

    override suspend fun doExecute(
        args: DeleteReceivedTrustMarkArgs,
        applyDuring: (DeleteReceivedTrustMarkArgs) -> DeleteReceivedTrustMarkArgs
    ): IdkResult<ReceivedTrustMark, FederationError> {
        val (tenantId, trustMarkId) = applyDuring(args)
        val username = tenantId

        logger.info("Attempting to delete trust mark ID: $trustMarkId for account: $username")

        // Check if trust mark exists for this account
        val existing = receivedTrustMarkQueries.findByAccountIdAndId(tenantId, trustMarkId).executeAsOneOrNull()
        if (existing == null) {
            logger.error("Trust mark not found with ID: $trustMarkId for account: $username")
            return federationErr(ReceivedTrustMarkNotFoundError(trustMarkId, username))
        }

        return try {
            val deletedTrustMark = receivedTrustMarkQueries.delete(trustMarkId).executeAsOneOrNull()

            if (deletedTrustMark != null) {
                logger.info("Successfully deleted trust mark ID: $trustMarkId for account: $username")
                IdkResult.ok(deletedTrustMark.toDTO())
            } else {
                logger.error("Failed to delete trust mark ID: $trustMarkId for account: $username")
                federationErr(ServerError("Failed to delete received trust mark"))
            }
        } catch (e: Exception) {
            logger.error("Failed to delete trust mark ID: $trustMarkId for account: $username", e)
            federationErr(ServerError("Failed to delete received trust mark", e.message, e))
        }
    }
}
