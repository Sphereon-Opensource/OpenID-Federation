package com.sphereon.openid.fed.services.command.authorityHint

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
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
) : TypedServiceCommandAdapter<FindAuthorityHintsByAccountArgs, List<AuthorityHint>>(
    commandId = FindAuthorityHintsByAccountCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<FindAuthorityHintsByAccountArgs>(),
    outputTypeToken = typeToken<List<AuthorityHint>>()
), FindAuthorityHintsByAccountCommand {

    private val logger = Log.app().withTag("FindAuthorityHintsByAccountCommand")
    private val authorityHintQueries = Persistence.authorityHintQueries

    override suspend fun doExecute(
        args: FindAuthorityHintsByAccountArgs,
        applyDuring: (FindAuthorityHintsByAccountArgs) -> FindAuthorityHintsByAccountArgs
    ): IdkResult<List<AuthorityHint>, IdkError> {
        val (tenantId) = applyDuring(args)

        logger.debug("Finding authority hints for account: ${tenantId}")

        return try {
            val authorityHints = authorityHintQueries.findByAccountId(tenantId)
                .executeAsList()
                .map { it.toDTO() }
            logger.info("Found ${authorityHints.size} authority hints for account: ${tenantId}")
            IdkResult.ok(authorityHints)
        } catch (e: Exception) {
            logger.error("Failed to find authority hints for account: ${tenantId}", e)
            federationErr(ServerError("Failed to retrieve authority hints", e.message, e))
        }
    }
}
