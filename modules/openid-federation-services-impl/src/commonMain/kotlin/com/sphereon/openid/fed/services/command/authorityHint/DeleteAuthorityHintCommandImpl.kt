package com.sphereon.openid.fed.services.command.authorityHint

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.EntityNotFoundError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AuthorityHint
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the DeleteAuthorityHintCommand.
 * Deletes an authority hint.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteAuthorityHintCommand::class)
class DeleteAuthorityHintCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<DeleteAuthorityHintArgs, AuthorityHint, FederationError>(
    id = DeleteAuthorityHintCommand.COMMAND_ID,
    execution = execution
), DeleteAuthorityHintCommand {

    private val logger = Log.app().withTag("DeleteAuthorityHintCommand")
    private val authorityHintQueries = Persistence.authorityHintQueries

    override suspend fun deleteAuthorityHint(account: Account, id: String): IdkResult<AuthorityHint, FederationError> {
        return execute(DeleteAuthorityHintArgs(account, id), execution.sessionContext)
    }

    override suspend fun doExecute(
        args: DeleteAuthorityHintArgs,
        sessionContext: SessionContext,
        applyDuring: (DeleteAuthorityHintArgs) -> DeleteAuthorityHintArgs
    ): IdkResult<AuthorityHint, FederationError> {
        val (account, authorityHintId) = applyDuring(args)

        logger.debug("Attempting to delete authority hint with id: $authorityHintId for account: ${account.username}")

        val authorityHint = authorityHintQueries
            .findByAccountIdAndId(account.id, authorityHintId)
            .executeAsOneOrNull()

        if (authorityHint == null) {
            logger.error("Authority hint not found with id: $authorityHintId for account: ${account.username}")
            return IdkResult.err(EntityNotFoundError("authority_hint:$authorityHintId"))
        }

        return try {
            val deleted = authorityHintQueries.delete(authorityHintId)
                .executeAsOneOrNull()
                ?.toDTO()

            if (deleted != null) {
                logger.info("Successfully deleted authority hint with id: $authorityHintId for account: ${account.username}")
                IdkResult.ok(deleted)
            } else {
                logger.error("Failed to delete authority hint with id: $authorityHintId for account: ${account.username}")
                IdkResult.err(ServerError(Constants.FAILED_TO_DELETE_AUTHORITY_HINT))
            }
        } catch (e: Exception) {
            logger.error("Failed to delete authority hint with id: $authorityHintId for account: ${account.username}", e)
            IdkResult.err(ServerError("Failed to delete authority hint", e.message, e))
        }
    }
}
