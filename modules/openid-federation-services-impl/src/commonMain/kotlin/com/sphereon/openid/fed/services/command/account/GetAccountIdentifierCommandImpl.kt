package com.sphereon.openid.fed.services.command.account

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.services.config.AccountServiceConfig
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetAccountIdentifierCommand::class)
class GetAccountIdentifierCommandImpl(
    execution: SessionExecution,
    private val config: AccountServiceConfig
) : ExecutionScopedCommandAdapter<GetAccountIdentifierArgs, String, FederationError>(
    id = GetAccountIdentifierCommand.COMMAND_ID,
    execution = execution
), GetAccountIdentifierCommand {

    private val logger = Log.app().withTag("GetAccountIdentifierCommand")

    override suspend fun getAccountIdentifierByAccount(account: Account): IdkResult<String, FederationError> =
        execute(GetAccountIdentifierArgs(account), execution.sessionContext)

    override suspend fun doExecute(
        args: GetAccountIdentifierArgs,
        sessionContext: SessionContext,
        applyDuring: (GetAccountIdentifierArgs) -> GetAccountIdentifierArgs
    ): IdkResult<String, FederationError> {
        val request = applyDuring(args)
        val account = request.account

        account.identifier?.let {
            logger.debug("Found explicit identifier for username: ${account.username}")
            return IdkResult.ok(it)
        }

        if (config.rootIdentifier.isBlank()) {
            return IdkResult.err(InvalidRequestError("Root identifier is not configured"))
        }

        val computedIdentifier = if (account.username == Constants.DEFAULT_ROOT_USERNAME) {
            config.rootIdentifier
        } else {
            "${config.rootIdentifier}/${account.username}"
        }
        logger.debug("Using identifier for username: ${account.username}: $computedIdentifier")
        return IdkResult.ok(computedIdentifier)
    }
}
