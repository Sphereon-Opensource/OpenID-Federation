package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.MetadataPolicyAlreadyExistsError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.MetadataPolicy
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
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
) : TypedServiceCommandAdapter<CreateMetadataPolicyArgs, MetadataPolicy>(
    commandId = CreateMetadataPolicyCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<CreateMetadataPolicyArgs>(),
    outputTypeToken = typeToken<MetadataPolicy>()
), CreateMetadataPolicyCommand {

    private val logger = Log.app().withTag("CreateMetadataPolicyCommand")
    private val metadataPolicyQueries = Persistence.metadataPolicyQueries

    override suspend fun doExecute(
        args: CreateMetadataPolicyArgs,
        applyDuring: (CreateMetadataPolicyArgs) -> CreateMetadataPolicyArgs
    ): IdkResult<MetadataPolicy, IdkError> {
        val (account, key, policy) = applyDuring(args)

        logger.info("Creating entity configuration metadata policy for account: ${account.username}, key: $key")
        logger.debug("Using account with ID: ${account.id}")

        val policyAlreadyExists = metadataPolicyQueries
            .findByAccountIdAndKey(account.id, key)
            .executeAsOneOrNull()

        if (policyAlreadyExists != null) {
            logger.error("Metadata policy already exists for account ID: ${account.id}, key: $key")
            return federationErr(MetadataPolicyAlreadyExistsError(account.id, key))
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
                federationErr(ServerError(Constants.FAILED_TO_CREATE_ENTITY_CONFIGURATION_METADATA_POLICY))
            }
        } catch (e: Exception) {
            logger.error("Failed to create metadata policy for account: ${account.username}, key: $key", e)
            federationErr(ServerError("Failed to create metadata policy", e.message, e))
        }
    }
}
