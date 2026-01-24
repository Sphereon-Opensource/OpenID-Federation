package com.sphereon.openid.fed.services.command.account

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
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
@ContributesBinding(SessionScope::class, boundType = GetAllAccountsCommand::class)
class GetAllAccountsCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<Unit, List<Account>, FederationError>(
    id = GetAllAccountsCommand.COMMAND_ID,
    execution = execution
), GetAllAccountsCommand {

    private val logger = Log.app().withTag("GetAllAccountsCommand")
    private val accountQueries = Persistence.accountQueries

    override suspend fun getAllAccounts(): IdkResult<List<Account>, FederationError> =
        execute(Unit, execution.sessionContext)

    override suspend fun doExecute(
        args: Unit,
        sessionContext: SessionContext,
        applyDuring: (Unit) -> Unit
    ): IdkResult<List<Account>, FederationError> {
        applyDuring(args)
        logger.debug("Retrieving all accounts")
        return try {
            val accounts = accountQueries.findAll().executeAsList()
            logger.debug("Found ${accounts.size} accounts")
            IdkResult.ok(accounts.map { it.toDTO() })
        } catch (e: Exception) {
            logger.error("Failed to retrieve accounts: ${e.message}", e)
            IdkResult.err(ServerError("Failed to retrieve accounts", e.message, e))
        }
    }
}
