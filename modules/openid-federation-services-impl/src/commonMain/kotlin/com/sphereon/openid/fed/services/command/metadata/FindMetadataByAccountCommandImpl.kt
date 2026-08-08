package com.sphereon.openid.fed.services.command.metadata

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
import com.sphereon.openid.fed.openapi.models.Metadata
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the FindMetadataByAccountCommand.
 * Finds all metadata entries for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FindMetadataByAccountCommand>())
class FindMetadataByAccountCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<FindMetadataByAccountArgs, List<Metadata>, FederationError>(
    commandId = FindMetadataByAccountCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<FindMetadataByAccountArgs>(),
    outputTypeToken = typeToken<List<Metadata>>()
), FindMetadataByAccountCommand {

    private val logger = execution.federationLogger("FindMetadataByAccountCommand")
    private val metadataQueries = Persistence.metadataQueries

    override suspend fun doExecute(
        args: FindMetadataByAccountArgs,
        applyDuring: (FindMetadataByAccountArgs) -> FindMetadataByAccountArgs
    ): IdkResult<List<Metadata>, FederationError> {
        val (tenantId) = applyDuring(args)

        logger.debug("Finding metadata for account: ${tenantId}")
        logger.debug("Using account with ID: ${tenantId}")

        return try {
            val metadataList = metadataQueries.findByAccountId(tenantId).executeAsList()
            logger.debug("Found ${metadataList.size} metadata entries for account: ${tenantId}")
            IdkResult.ok(metadataList.map { it.toDTO() })
        } catch (e: Exception) {
            logger.error("Failed to find metadata for account: ${tenantId}", e)
            federationErr(ServerError("Failed to retrieve metadata", e.message, e))
        }
    }
}
