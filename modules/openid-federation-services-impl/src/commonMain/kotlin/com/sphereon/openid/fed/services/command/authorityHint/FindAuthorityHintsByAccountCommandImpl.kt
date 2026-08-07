package com.sphereon.openid.fed.services.command.authorityHint

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.AuthorityHint
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the FindAuthorityHintsByAccountCommand.
 * Finds all authority hints for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FindAuthorityHintsByAccountCommand>())
class FindAuthorityHintsByAccountCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<FindAuthorityHintsByAccountArgs, List<AuthorityHint>, FederationError>(
    commandId = FindAuthorityHintsByAccountCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<FindAuthorityHintsByAccountArgs>(),
    outputTypeToken = typeToken<List<AuthorityHint>>()
), FindAuthorityHintsByAccountCommand {

    private val logger = execution.federationLogger("FindAuthorityHintsByAccountCommand")
    private val authorityHintQueries = Persistence.authorityHintQueries

    override suspend fun doExecute(
        args: FindAuthorityHintsByAccountArgs,
        applyDuring: (FindAuthorityHintsByAccountArgs) -> FindAuthorityHintsByAccountArgs
    ): IdkResult<List<AuthorityHint>, FederationError> {
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
