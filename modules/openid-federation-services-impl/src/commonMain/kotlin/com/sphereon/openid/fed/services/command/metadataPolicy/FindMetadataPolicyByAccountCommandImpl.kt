package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
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
) : TypedServiceCommandAdapter<FindMetadataPolicyByAccountArgs, List<MetadataPolicy>>(
    commandId = FindMetadataPolicyByAccountCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<FindMetadataPolicyByAccountArgs>(),
    outputTypeToken = typeToken<List<MetadataPolicy>>()
), FindMetadataPolicyByAccountCommand {

    private val logger = Log.app().withTag("FindMetadataPolicyByAccountCommand")
    private val metadataPolicyQueries = Persistence.metadataPolicyQueries

    override suspend fun doExecute(
        args: FindMetadataPolicyByAccountArgs,
        applyDuring: (FindMetadataPolicyByAccountArgs) -> FindMetadataPolicyByAccountArgs
    ): IdkResult<List<MetadataPolicy>, IdkError> {
        val (account) = applyDuring(args)

        logger.debug("Finding metadata policy for account: ${account.username}")
        logger.debug("Using account with ID: ${account.id}")

        return try {
            val policyList = metadataPolicyQueries.findByAccountId(account.id).executeAsList()
            logger.debug("Found ${policyList.size} metadata policy entries for account: ${account.username}")
            IdkResult.ok(policyList.map { it.toDTO() })
        } catch (e: Exception) {
            logger.error("Failed to find metadata policy for account: ${account.username}", e)
            federationErr(ServerError("Failed to retrieve metadata policies", e.message, e))
        }
    }
}
