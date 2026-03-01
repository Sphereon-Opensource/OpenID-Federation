package com.sphereon.openid.fed.account.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.account.config.AccountServiceConfig
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetAccountIdentifierCommand::class)
class GetAccountIdentifierCommandImpl(
    execution: SessionExecution,
    private val config: AccountServiceConfig
) : TypedServiceCommandAdapter<GetAccountIdentifierArgs, String>(
    commandId = GetAccountIdentifierCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<GetAccountIdentifierArgs>(),
    outputTypeToken = typeToken<String>()
), GetAccountIdentifierCommand {

    private val logger = Log.app().withTag("GetAccountIdentifierCommand")

    override suspend fun doExecute(
        args: GetAccountIdentifierArgs,
        applyDuring: (GetAccountIdentifierArgs) -> GetAccountIdentifierArgs
    ): IdkResult<String, IdkError> {
        val request = applyDuring(args)
        val account = request.account

        account.identifier?.let {
            logger.debug("Found explicit identifier for username: ${account.username}")
            return IdkResult.ok(it)
        }

        if (config.rootIdentifier.isBlank()) {
            return federationErr(InvalidRequestError("Root identifier is not configured"))
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
