package com.sphereon.openid.fed.account.command

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.UnitInputServiceCommandAdapter
import com.sphereon.di.session.SessionScope
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
@ContributesBinding(SessionScope::class, binding = binding<GetAllAccountsCommand>())
class GetAllAccountsCommandImpl(
    execution: SessionExecution
) : UnitInputServiceCommandAdapter<List<Account>, FederationError>(
    commandId = GetAllAccountsCommand.COMMAND_ID,
    execution = execution,
    outputTypeToken = typeToken<List<Account>>()
), GetAllAccountsCommand {

    private val logger = execution.federationLogger("GetAllAccountsCommand")
    private val accountQueries = Persistence.accountQueries

    override suspend fun doExecute(
        args: Unit,
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
            federationErr(ServerError("Failed to retrieve accounts", e.message, e))
        }
    }
}
