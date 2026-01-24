package com.sphereon.openid.fed.client.command.entityConfiguration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.services.entityConfigurationStatementService.EntityConfigurationStatementServiceConst
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidEntityConfigurationError
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.FederationEntityMetadata
import kotlinx.serialization.json.jsonObject
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the GetFederationEndpointsCommand.
 * Extracts federation endpoints from an entity configuration statement.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetFederationEndpointsCommand::class)
class GetFederationEndpointsCommandImpl(
    execution: SessionExecution,
    private val context: FederationContext
) : ExecutionScopedCommandAdapter<GetFederationEndpointsArgs, FederationEntityMetadata, FederationError>(
    id = GetFederationEndpointsCommand.COMMAND_ID,
    execution = execution
), GetFederationEndpointsCommand {

    private val logger = EntityConfigurationStatementServiceConst.LOG

    override suspend fun getFederationEndpoints(
        entityConfiguration: EntityConfigurationStatement
    ): IdkResult<FederationEntityMetadata, FederationError> {
        return execute(
            GetFederationEndpointsArgs(entityConfiguration),
            execution.sessionContext
        )
    }

    override suspend fun doExecute(
        args: GetFederationEndpointsArgs,
        sessionContext: SessionContext,
        applyDuring: (GetFederationEndpointsArgs) -> GetFederationEndpointsArgs
    ): IdkResult<FederationEntityMetadata, FederationError> {
        val (entityConfiguration) = applyDuring(args)

        logger.debug("Extracting federation endpoints from EntityConfigurationStatement")

        val entityId = entityConfiguration.sub ?: "unknown"
        val metadata = entityConfiguration.metadata
            ?: run {
                logger.error("No metadata found in entity configuration")
                return IdkResult.err(InvalidEntityConfigurationError(entityId, "No metadata found in entity configuration"))
            }

        val federationMetadata = metadata["federation_entity"]?.jsonObject
            ?: run {
                logger.error("No federation_entity metadata found in entity configuration")
                return IdkResult.err(InvalidEntityConfigurationError(entityId, "No federation_entity metadata found in entity configuration"))
            }

        return try {
            logger.debug("Decoding federation metadata into FederationEntityMetadata")
            val result = context.json.decodeFromJsonElement(
                FederationEntityMetadata.serializer(),
                federationMetadata
            )
            logger.debug("Successfully extracted federation endpoints")
            IdkResult.ok(result)
        } catch (e: Exception) {
            logger.error("Failed to parse federation_entity metadata", e)
            IdkResult.err(InvalidEntityConfigurationError(entityId, "Failed to parse federation_entity metadata: ${e.message}", e))
        }
    }
}
