package com.sphereon.openid.fed.account.command

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.account.mappers.toDTO
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetAccountByUsernameCommand>())
class GetAccountByUsernameCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<GetAccountByUsernameArgs, Account, FederationError>(
    commandId = GetAccountByUsernameCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<GetAccountByUsernameArgs>(),
    outputTypeToken = typeToken<Account>()
), GetAccountByUsernameCommand {

    private val logger = execution.federationLogger("GetAccountByUsernameCommand")
    private val accountQueries = Persistence.accountQueries

    override suspend fun doExecute(
        args: GetAccountByUsernameArgs,
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
                federationErr(AccountNotFoundError(request.username))
            }
        } catch (e: Exception) {
            logger.error("Failed to get account by username: ${e.message}", e)
            federationErr(ServerError("Failed to get account", e.message, e))
        }
    }
}
