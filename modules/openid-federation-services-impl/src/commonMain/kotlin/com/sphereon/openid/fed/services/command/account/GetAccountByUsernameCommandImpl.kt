package com.sphereon.openid.fed.services.command.account

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetAccountByUsernameCommand::class)
class GetAccountByUsernameCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<GetAccountByUsernameArgs, Account, FederationError>(
    id = GetAccountByUsernameCommand.COMMAND_ID,
    execution = execution
), GetAccountByUsernameCommand {

    private val logger = Log.app().withTag("GetAccountByUsernameCommand")
    private val accountQueries = Persistence.accountQueries

    override suspend fun getAccountByUsername(username: String): IdkResult<Account, FederationError> =
        execute(GetAccountByUsernameArgs(username), execution.sessionContext)

    override suspend fun doExecute(
        args: GetAccountByUsernameArgs,
        sessionContext: SessionContext,
        applyDuring: (GetAccountByUsernameArgs) -> GetAccountByUsernameArgs
    ): IdkResult<Account, FederationError> {
        val request = applyDuring(args)
        logger.debug("Getting account by username: ${request.username}")
        return try {
            val account = accountQueries.findByUsername(request.username).executeAsOneOrNull()?.toDTO()
            if (account != null) {
                IdkResult.ok(account)
            } else {
                logger.error("Account not found for username: ${request.username}")
                IdkResult.err(AccountNotFoundError(request.username))
            }
        } catch (e: Exception) {
            logger.error("Failed to get account by username: ${e.message}", e)
            IdkResult.err(ServerError("Failed to get account", e.message, e))
        }
    }
}
