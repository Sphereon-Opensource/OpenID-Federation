package com.sphereon.openid.fed.account.command

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.ForbiddenError
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
@ContributesBinding(SessionScope::class, binding = binding<DeleteAccountCommand>())
class DeleteAccountCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<DeleteAccountArgs, Account, FederationError>(
    commandId = DeleteAccountCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<DeleteAccountArgs>(),
    outputTypeToken = typeToken<Account>()
), DeleteAccountCommand {

    private val logger = execution.federationLogger("DeleteAccountCommand")
    private val accountQueries = Persistence.accountQueries

    override suspend fun doExecute(
        args: DeleteAccountArgs,
        applyDuring: (DeleteAccountArgs) -> DeleteAccountArgs
    ): IdkResult<Account, FederationError> {
        val request = applyDuring(args)
        val account = request.account
        logger.info("Starting account deletion process for username: ${account.username}")

        if (account.username == Constants.DEFAULT_ROOT_USERNAME) {
            logger.error("Account deletion failed: Attempted to delete root account")
            return federationErr(ForbiddenError(Constants.ROOT_ACCOUNT_CANNOT_BE_DELETED))
        }

        return try {
            val deletedAccount = accountQueries.delete(account.id).executeAsOne()
            logger.info("Successfully deleted account - Username: ${account.username}, ID: ${account.id}")
            IdkResult.ok(deletedAccount.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to delete account: ${e.message}", e)
            federationErr(ServerError("Failed to delete account", e.message, e))
        }
    }
}
