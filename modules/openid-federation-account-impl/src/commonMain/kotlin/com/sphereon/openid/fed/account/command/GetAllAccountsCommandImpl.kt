package com.sphereon.openid.fed.account.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.UnitInputServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.account.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetAllAccountsCommand::class)
class GetAllAccountsCommandImpl(
    execution: SessionExecution
) : UnitInputServiceCommandAdapter<List<Account>>(
    commandId = GetAllAccountsCommand.COMMAND_ID,
    execution = execution,
    outputTypeToken = typeToken<List<Account>>()
), GetAllAccountsCommand {

    private val logger = Log.app().withTag("GetAllAccountsCommand")
    private val accountQueries = Persistence.accountQueries

    override suspend fun doExecute(
        args: Unit,
        applyDuring: (Unit) -> Unit
    ): IdkResult<List<Account>, IdkError> {
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
