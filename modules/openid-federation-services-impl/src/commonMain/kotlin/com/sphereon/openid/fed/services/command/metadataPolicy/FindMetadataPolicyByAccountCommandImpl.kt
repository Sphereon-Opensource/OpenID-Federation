package com.sphereon.openid.fed.services.command.metadataPolicy

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
import com.sphereon.openid.fed.openapi.models.MetadataPolicy
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the FindMetadataPolicyByAccountCommand.
 * Finds all metadata policy entries for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FindMetadataPolicyByAccountCommand>())
class FindMetadataPolicyByAccountCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<FindMetadataPolicyByAccountArgs, List<MetadataPolicy>, FederationError>(
    commandId = FindMetadataPolicyByAccountCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<FindMetadataPolicyByAccountArgs>(),
    outputTypeToken = typeToken<List<MetadataPolicy>>()
), FindMetadataPolicyByAccountCommand {

    private val logger = execution.federationLogger("FindMetadataPolicyByAccountCommand")
    private val metadataPolicyQueries = Persistence.metadataPolicyQueries

    override suspend fun doExecute(
        args: FindMetadataPolicyByAccountArgs,
        applyDuring: (FindMetadataPolicyByAccountArgs) -> FindMetadataPolicyByAccountArgs
    ): IdkResult<List<MetadataPolicy>, FederationError> {
        val (tenantId) = applyDuring(args)

        logger.debug("Finding metadata policy for account: ${tenantId}")
        logger.debug("Using account with ID: ${tenantId}")

        return try {
            val policyList = metadataPolicyQueries.findByAccountId(tenantId).executeAsList()
            logger.debug("Found ${policyList.size} metadata policy entries for account: ${tenantId}")
            IdkResult.ok(policyList.map { it.toDTO() })
        } catch (e: Exception) {
            logger.error("Failed to find metadata policy for account: ${tenantId}", e)
            federationErr(ServerError("Failed to retrieve metadata policies", e.message, e))
        }
    }
}
