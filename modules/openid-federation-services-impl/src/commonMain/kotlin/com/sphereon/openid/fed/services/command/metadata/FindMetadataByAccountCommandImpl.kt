package com.sphereon.openid.fed.services.command.metadata

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.Metadata
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the FindMetadataByAccountCommand.
 * Finds all metadata entries for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FindMetadataByAccountCommand::class)
class FindMetadataByAccountCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<FindMetadataByAccountArgs, List<Metadata>, FederationError>(
    id = FindMetadataByAccountCommand.COMMAND_ID,
    execution = execution
), FindMetadataByAccountCommand {

    private val logger = Log.app().withTag("FindMetadataByAccountCommand")
    private val metadataQueries = Persistence.metadataQueries

    override suspend fun findByAccount(account: Account): IdkResult<List<Metadata>, FederationError> {
        return execute(FindMetadataByAccountArgs(account))
    }

    override suspend fun doExecute(
        args: FindMetadataByAccountArgs,
        applyDuring: (FindMetadataByAccountArgs) -> FindMetadataByAccountArgs
    ): IdkResult<List<Metadata>, FederationError> {
        val (account) = applyDuring(args)

        logger.debug("Finding metadata for account: ${account.username}")
        logger.debug("Using account with ID: ${account.id}")

        return try {
            val metadataList = metadataQueries.findByAccountId(account.id).executeAsList()
            logger.debug("Found ${metadataList.size} metadata entries for account: ${account.username}")
            IdkResult.ok(metadataList.map { it.toDTO() })
        } catch (e: Exception) {
            logger.error("Failed to find metadata for account: ${account.username}", e)
            IdkResult.err(ServerError("Failed to retrieve metadata", e.message, e))
        }
    }
}
