package com.sphereon.openid.fed.services.command.authorityHint

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AuthorityHint
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the CreateAuthorityHintCommand.
 * Creates a new authority hint for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateAuthorityHintCommand::class)
class CreateAuthorityHintCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<CreateAuthorityHintArgs, AuthorityHint, FederationError>(
    id = CreateAuthorityHintCommand.COMMAND_ID,
    execution = execution
), CreateAuthorityHintCommand {

    private val logger = Log.app().withTag("CreateAuthorityHintCommand")
    private val authorityHintQueries = Persistence.authorityHintQueries

    override suspend fun createAuthorityHint(account: Account, identifier: String): IdkResult<AuthorityHint, FederationError> {
        return execute(CreateAuthorityHintArgs(account, identifier), execution.sessionContext)
    }

    override suspend fun doExecute(
        args: CreateAuthorityHintArgs,
        sessionContext: SessionContext,
        applyDuring: (CreateAuthorityHintArgs) -> CreateAuthorityHintArgs
    ): IdkResult<AuthorityHint, FederationError> {
        val (account, identifier) = applyDuring(args)

        logger.debug("Attempting to create authority hint for account: ${account.username} with identifier: $identifier")

        val existingAuthorityHint = authorityHintQueries
            .findByAccountIdAndIdentifier(account.id, identifier)
            .executeAsOneOrNull()

        if (existingAuthorityHint != null) {
            logger.error("Authority hint already exists for account: ${account.username}, identifier: $identifier")
            return IdkResult.err(InvalidRequestError(Constants.AUTHORITY_HINT_ALREADY_EXISTS))
        }

        return try {
            val created = authorityHintQueries.create(account.id, identifier).executeAsOneOrNull()?.toDTO()
            if (created != null) {
                logger.info("Successfully created authority hint for account: ${account.username} with identifier: $identifier")
                IdkResult.ok(created)
            } else {
                logger.error("Failed to create authority hint for account: ${account.username} with identifier: $identifier")
                IdkResult.err(ServerError(Constants.FAILED_TO_CREATE_AUTHORITY_HINT))
            }
        } catch (e: Exception) {
            logger.error("Failed to create authority hint for account: ${account.username} with identifier: $identifier", e)
            IdkResult.err(ServerError("Failed to create authority hint", e.message, e))
        }
    }
}
