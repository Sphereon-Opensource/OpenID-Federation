package com.sphereon.openid.fed.services.command.authorityHint

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.EntityNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
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
) : TypedServiceCommandAdapter<DeleteAuthorityHintArgs, AuthorityHint>(
    commandId = DeleteAuthorityHintCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<DeleteAuthorityHintArgs>(),
    outputTypeToken = typeToken<AuthorityHint>()
), DeleteAuthorityHintCommand {

    private val logger = execution.federationLogger("DeleteAuthorityHintCommand")
    private val authorityHintQueries = Persistence.authorityHintQueries

    override suspend fun doExecute(
        args: DeleteAuthorityHintArgs,
        applyDuring: (DeleteAuthorityHintArgs) -> DeleteAuthorityHintArgs
    ): IdkResult<AuthorityHint, IdkError> {
        val (tenantId, authorityHintId) = applyDuring(args)

        logger.debug("Attempting to delete authority hint with id: $authorityHintId for account: ${tenantId}")

        val authorityHint = authorityHintQueries
            .findByAccountIdAndId(tenantId, authorityHintId)
            .executeAsOneOrNull()

        if (authorityHint == null) {
            logger.error("Authority hint not found with id: $authorityHintId for account: ${tenantId}")
            return federationErr(EntityNotFoundError("authority_hint:$authorityHintId"))
        }

        return try {
            val deleted = authorityHintQueries.delete(authorityHintId)
                .executeAsOneOrNull()
                ?.toDTO()

            if (deleted != null) {
                logger.info("Successfully deleted authority hint with id: $authorityHintId for account: ${tenantId}")
                IdkResult.ok(deleted)
            } else {
                logger.error("Failed to delete authority hint with id: $authorityHintId for account: ${tenantId}")
                federationErr(ServerError(Constants.FAILED_TO_DELETE_AUTHORITY_HINT))
            }
        } catch (e: Exception) {
            logger.error("Failed to delete authority hint with id: $authorityHintId for account: ${tenantId}", e)
            federationErr(ServerError("Failed to delete authority hint", e.message, e))
        }
    }
}
