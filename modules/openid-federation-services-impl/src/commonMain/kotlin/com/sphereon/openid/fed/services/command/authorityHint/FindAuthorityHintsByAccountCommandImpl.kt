package com.sphereon.openid.fed.services.command.authorityHint

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AuthorityHint
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the FindAuthorityHintsByAccountCommand.
 * Finds all authority hints for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FindAuthorityHintsByAccountCommand::class)
class FindAuthorityHintsByAccountCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<FindAuthorityHintsByAccountArgs, List<AuthorityHint>, FederationError>(
    id = FindAuthorityHintsByAccountCommand.COMMAND_ID,
    execution = execution
), FindAuthorityHintsByAccountCommand {

    private val logger = Log.app().withTag("FindAuthorityHintsByAccountCommand")
    private val authorityHintQueries = Persistence.authorityHintQueries

    override suspend fun findByAccount(account: Account): IdkResult<List<AuthorityHint>, FederationError> {
        return execute(FindAuthorityHintsByAccountArgs(account), execution.sessionContext)
    }

    override suspend fun doExecute(
        args: FindAuthorityHintsByAccountArgs,
        sessionContext: SessionContext,
        applyDuring: (FindAuthorityHintsByAccountArgs) -> FindAuthorityHintsByAccountArgs
    ): IdkResult<List<AuthorityHint>, FederationError> {
        val (account) = applyDuring(args)

        logger.debug("Finding authority hints for account: ${account.username}")

        return try {
            val authorityHints = authorityHintQueries.findByAccountId(account.id)
                .executeAsList()
                .map { it.toDTO() }
            logger.info("Found ${authorityHints.size} authority hints for account: ${account.username}")
            IdkResult.ok(authorityHints)
        } catch (e: Exception) {
            logger.error("Failed to find authority hints for account: ${account.username}", e)
            IdkResult.err(ServerError("Failed to retrieve authority hints", e.message, e))
        }
    }
}
