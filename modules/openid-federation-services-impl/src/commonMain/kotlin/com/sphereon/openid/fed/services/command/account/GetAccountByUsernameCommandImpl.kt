package com.sphereon.openid.fed.services.command.account

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
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
) : TypedServiceCommandAdapter<GetAccountByUsernameArgs, Account>(
    commandId = GetAccountByUsernameCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<GetAccountByUsernameArgs>(),
    outputTypeToken = typeToken<Account>()
), GetAccountByUsernameCommand {

    private val logger = Log.app().withTag("GetAccountByUsernameCommand")
    private val accountQueries = Persistence.accountQueries

    override suspend fun doExecute(
        args: GetAccountByUsernameArgs,
        applyDuring: (GetAccountByUsernameArgs) -> GetAccountByUsernameArgs
    ): IdkResult<Account, IdkError> {
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
