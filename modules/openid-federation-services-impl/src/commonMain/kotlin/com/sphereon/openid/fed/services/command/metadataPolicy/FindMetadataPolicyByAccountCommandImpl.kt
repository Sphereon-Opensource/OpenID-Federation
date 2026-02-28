package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.MetadataPolicy
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the FindMetadataPolicyByAccountCommand.
 * Finds all metadata policy entries for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FindMetadataPolicyByAccountCommand::class)
class FindMetadataPolicyByAccountCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<FindMetadataPolicyByAccountArgs, List<MetadataPolicy>, FederationError>(
    id = FindMetadataPolicyByAccountCommand.COMMAND_ID,
    execution = execution
), FindMetadataPolicyByAccountCommand {

    private val logger = Log.app().withTag("FindMetadataPolicyByAccountCommand")
    private val metadataPolicyQueries = Persistence.metadataPolicyQueries

    override suspend fun findByAccount(account: Account): IdkResult<List<MetadataPolicy>, FederationError> {
        return execute(FindMetadataPolicyByAccountArgs(account))
    }

    override suspend fun doExecute(
        args: FindMetadataPolicyByAccountArgs,
        applyDuring: (FindMetadataPolicyByAccountArgs) -> FindMetadataPolicyByAccountArgs
    ): IdkResult<List<MetadataPolicy>, FederationError> {
        val (account) = applyDuring(args)

        logger.debug("Finding metadata policy for account: ${account.username}")
        logger.debug("Using account with ID: ${account.id}")

        return try {
            val policyList = metadataPolicyQueries.findByAccountId(account.id).executeAsList()
            logger.debug("Found ${policyList.size} metadata policy entries for account: ${account.username}")
            IdkResult.ok(policyList.map { it.toDTO() })
        } catch (e: Exception) {
            logger.error("Failed to find metadata policy for account: ${account.username}", e)
            IdkResult.err(ServerError("Failed to retrieve metadata policies", e.message, e))
        }
    }
}
