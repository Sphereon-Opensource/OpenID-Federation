package com.sphereon.openid.fed.services.command.account

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ForbiddenError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteAccountCommand::class)
class DeleteAccountCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<DeleteAccountArgs, Account, FederationError>(
    id = DeleteAccountCommand.COMMAND_ID,
    execution = execution
), DeleteAccountCommand {

    private val logger = Log.app().withTag("DeleteAccountCommand")
    private val accountQueries = Persistence.accountQueries

    override suspend fun deleteAccount(account: Account): IdkResult<Account, FederationError> =
        execute(DeleteAccountArgs(account), execution.sessionContext)

    override suspend fun doExecute(
        args: DeleteAccountArgs,
        sessionContext: SessionContext,
        applyDuring: (DeleteAccountArgs) -> DeleteAccountArgs
    ): IdkResult<Account, FederationError> {
        val request = applyDuring(args)
        val account = request.account
        logger.info("Starting account deletion process for username: ${account.username}")

        if (account.username == Constants.DEFAULT_ROOT_USERNAME) {
            logger.error("Account deletion failed: Attempted to delete root account")
            return IdkResult.err(ForbiddenError(Constants.ROOT_ACCOUNT_CANNOT_BE_DELETED))
        }

        return try {
            val deletedAccount = accountQueries.delete(account.id).executeAsOne()
            logger.info("Successfully deleted account - Username: ${account.username}, ID: ${account.id}")
            IdkResult.ok(deletedAccount.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to delete account: ${e.message}", e)
            IdkResult.err(ServerError("Failed to delete account", e.message, e))
        }
    }
}
