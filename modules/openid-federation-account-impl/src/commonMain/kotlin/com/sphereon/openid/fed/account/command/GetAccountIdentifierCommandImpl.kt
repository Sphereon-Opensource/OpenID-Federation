package com.sphereon.openid.fed.account.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.account.config.AccountServiceConfig
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetAccountIdentifierCommand>())
class GetAccountIdentifierCommandImpl(
    execution: SessionExecution,
    private val config: AccountServiceConfig
) : TypedServiceCommandAdapter<GetAccountIdentifierArgs, String>(
    commandId = GetAccountIdentifierCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<GetAccountIdentifierArgs>(),
    outputTypeToken = typeToken<String>()
), GetAccountIdentifierCommand {

    private val logger = execution.federationLogger("GetAccountIdentifierCommand")

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
