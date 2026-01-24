package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.MetadataPolicyAlreadyExistsError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.MetadataPolicy
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import kotlinx.serialization.json.JsonElement
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the CreateMetadataPolicyCommand.
 * Creates a new metadata policy entry for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateMetadataPolicyCommand::class)
class CreateMetadataPolicyCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<CreateMetadataPolicyArgs, MetadataPolicy, FederationError>(
    id = CreateMetadataPolicyCommand.COMMAND_ID,
    execution = execution
), CreateMetadataPolicyCommand {

    private val logger = Log.app().withTag("CreateMetadataPolicyCommand")
    private val metadataPolicyQueries = Persistence.metadataPolicyQueries

    override suspend fun createPolicy(account: Account, key: String, policy: JsonElement): IdkResult<MetadataPolicy, FederationError> {
        return execute(CreateMetadataPolicyArgs(account, key, policy), execution.sessionContext)
    }

    override suspend fun doExecute(
        args: CreateMetadataPolicyArgs,
        sessionContext: SessionContext,
        applyDuring: (CreateMetadataPolicyArgs) -> CreateMetadataPolicyArgs
    ): IdkResult<MetadataPolicy, FederationError> {
        val (account, key, policy) = applyDuring(args)

        logger.info("Creating entity configuration metadata policy for account: ${account.username}, key: $key")
        logger.debug("Using account with ID: ${account.id}")

        val policyAlreadyExists = metadataPolicyQueries
            .findByAccountIdAndKey(account.id, key)
            .executeAsOneOrNull()

        if (policyAlreadyExists != null) {
            logger.error("Metadata policy already exists for account ID: ${account.id}, key: $key")
            return IdkResult.err(MetadataPolicyAlreadyExistsError(account.id, key))
        }

        return try {
            val createdPolicy = metadataPolicyQueries
                .create(account.id, key, policy.toString())
                .executeAsOneOrNull()

            if (createdPolicy != null) {
                logger.info("Successfully created metadata policy with ID: ${createdPolicy.id}")
                IdkResult.ok(createdPolicy.toDTO())
            } else {
                logger.error("Failed to create metadata policy for account ID: ${account.id}, key: $key")
                IdkResult.err(ServerError(Constants.FAILED_TO_CREATE_ENTITY_CONFIGURATION_METADATA_POLICY))
            }
        } catch (e: Exception) {
            logger.error("Failed to create metadata policy for account: ${account.username}, key: $key", e)
            IdkResult.err(ServerError("Failed to create metadata policy", e.message, e))
        }
    }
}
