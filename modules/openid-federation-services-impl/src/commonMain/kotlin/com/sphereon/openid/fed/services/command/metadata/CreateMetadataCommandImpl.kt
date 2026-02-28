package com.sphereon.openid.fed.services.command.metadata

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.MetadataAlreadyExistsError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.Metadata
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import kotlinx.serialization.json.JsonElement
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the CreateMetadataCommand.
 * Creates a new metadata entry for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateMetadataCommand::class)
class CreateMetadataCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<CreateMetadataArgs, Metadata, FederationError>(
    id = CreateMetadataCommand.COMMAND_ID,
    execution = execution
), CreateMetadataCommand {

    private val logger = Log.app().withTag("CreateMetadataCommand")
    private val metadataQueries = Persistence.metadataQueries

    override suspend fun createMetadata(account: Account, key: String, metadata: JsonElement): IdkResult<Metadata, FederationError> {
        return execute(CreateMetadataArgs(account, key, metadata))
    }

    override suspend fun doExecute(
        args: CreateMetadataArgs,
        applyDuring: (CreateMetadataArgs) -> CreateMetadataArgs
    ): IdkResult<Metadata, FederationError> {
        val (account, key, metadata) = applyDuring(args)

        logger.info("Creating entity configuration metadata for account: ${account.username}, key: $key")
        logger.debug("Using account with ID: ${account.id}")

        val metadataAlreadyExists = metadataQueries
            .findByAccountIdAndKey(account.id, key)
            .executeAsOneOrNull()

        if (metadataAlreadyExists != null) {
            logger.error("Metadata already exists for account ID: ${account.id}, key: $key")
            return IdkResult.err(MetadataAlreadyExistsError(account.id, key))
        }

        return try {
            val createdMetadata = metadataQueries
                .create(account.id, key, metadata.toString())
                .executeAsOneOrNull()

            if (createdMetadata != null) {
                logger.info("Successfully created metadata with ID: ${createdMetadata.id}")
                IdkResult.ok(createdMetadata.toDTO())
            } else {
                logger.error("Failed to create metadata for account ID: ${account.id}, key: $key")
                IdkResult.err(ServerError(Constants.FAILED_TO_CREATE_ENTITY_CONFIGURATION_METADATA))
            }
        } catch (e: Exception) {
            logger.error("Failed to create metadata for account: ${account.username}, key: $key", e)
            IdkResult.err(ServerError("Failed to create metadata", e.message, e))
        }
    }
}
