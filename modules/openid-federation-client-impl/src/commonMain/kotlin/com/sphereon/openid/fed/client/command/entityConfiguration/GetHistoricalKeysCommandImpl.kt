package com.sphereon.openid.fed.client.command.entityConfiguration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.crypto.fetchAndVerifyJwt
import com.sphereon.openid.fed.client.crypto.verifyJwtSignature
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.client.services.entityConfigurationStatementService.EntityConfigurationStatementServiceConst
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidEntityConfigurationError
import com.sphereon.openid.fed.core.error.KeyNotFoundError
import com.sphereon.openid.fed.core.error.SignatureVerificationFailedError
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.FederationEntityMetadata
import com.sphereon.openid.fed.openapi.models.FederationHistoricalKeysResponse
import com.sphereon.openid.fed.openapi.models.HistoricalKey
import kotlinx.serialization.json.jsonObject
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the GetHistoricalKeysCommand.
 * Retrieves historical keys from the federation entity's historical keys endpoint.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetHistoricalKeysCommand::class)
class GetHistoricalKeysCommandImpl(
    execution: SessionExecution,
    private val context: FederationContext,
    private val getFederationEndpointsCommand: GetFederationEndpointsCommand
) : ExecutionScopedCommandAdapter<GetHistoricalKeysArgs, List<HistoricalKey>, FederationError>(
    id = GetHistoricalKeysCommand.COMMAND_ID,
    execution = execution
), GetHistoricalKeysCommand {

    private val logger = EntityConfigurationStatementServiceConst.LOG

    override suspend fun getHistoricalKeys(
        entityConfiguration: EntityConfigurationStatement
    ): IdkResult<List<HistoricalKey>, FederationError> {
        return execute(
            GetHistoricalKeysArgs(entityConfiguration),
            execution.sessionContext
        )
    }

    override suspend fun doExecute(
        args: GetHistoricalKeysArgs,
        sessionContext: SessionContext,
        applyDuring: (GetHistoricalKeysArgs) -> GetHistoricalKeysArgs
    ): IdkResult<List<HistoricalKey>, FederationError> {
        val (entityConfiguration) = applyDuring(args)

        logger.debug("Retrieving historical keys")

        // Fetch historical keys JWT
        val historicalKeysJwtResult = fetchHistoricalKeysJwt(entityConfiguration)
        if (historicalKeysJwtResult.isErr) {
            return IdkResult.err(historicalKeysJwtResult.error)
        }
        val historicalKeysJwt = historicalKeysJwtResult.value

        // Verify historical keys JWT
        val verifiedJwtResult = verifyHistoricalKeysJwt(entityConfiguration, historicalKeysJwt)
        if (verifiedJwtResult.isErr) {
            return IdkResult.err(verifiedJwtResult.error)
        }
        val verifiedJwt = verifiedJwtResult.value

        // Decode historical keys
        val entityId = entityConfiguration.sub ?: "unknown"
        return decodeHistoricalKeys(verifiedJwt, entityId)
    }

    private suspend fun fetchHistoricalKeysJwt(
        entityConfiguration: EntityConfigurationStatement
    ): IdkResult<String, FederationError> {
        val federationEndpointsResult = getFederationEndpointsCommand.getFederationEndpoints(entityConfiguration)
        if (federationEndpointsResult.isErr) {
            return IdkResult.err(federationEndpointsResult.error)
        }

        val federationEndpoints = federationEndpointsResult.value
        val entityId = entityConfiguration.sub ?: "unknown"
        val historicalKeysEndpoint = federationEndpoints.federationHistoricalKeysEndpoint
            ?: run {
                logger.error("No historical keys endpoint found in federation metadata")
                return IdkResult.err(InvalidEntityConfigurationError(entityId, "No historical keys endpoint found in federation metadata"))
            }

        logger.debug("Fetching historical keys from endpoint: $historicalKeysEndpoint")
        return try {
            val jwt = context.jwtService.fetchAndVerifyJwt(historicalKeysEndpoint, context.httpResolver)
            logger.debug("Successfully fetched historical keys JWT")
            IdkResult.ok(jwt)
        } catch (e: Exception) {
            logger.error("Failed to fetch historical keys", e)
            IdkResult.err(InvalidEntityConfigurationError(entityId, "Failed to fetch historical keys: ${e.message}", e))
        }
    }

    private suspend fun verifyHistoricalKeysJwt(
        entityConfiguration: EntityConfigurationStatement,
        jwt: String
    ): IdkResult<String, FederationError> {
        val decodedJwt = decodeJWTComponents(jwt)
        logger.debug("Successfully decoded historical keys JWT")

        val keyId = decodedJwt.header.kid ?: "unknown"
        val signingKey = entityConfiguration.jwks.propertyKeys?.find { it.kid == decodedJwt.header.kid }
            ?: run {
                logger.error("No matching key found for kid: $keyId")
                return IdkResult.err(KeyNotFoundError(keyId))
            }

        if (!context.jwtService.verifyJwtSignature(jwt, signingKey)) {
            return IdkResult.err(SignatureVerificationFailedError("Historical keys JWT signature verification failed"))
        }
        return IdkResult.ok(jwt)
    }

    private fun decodeHistoricalKeys(jwt: String, entityId: String): IdkResult<List<HistoricalKey>, FederationError> {
        return try {
            val decodedJwt = decodeJWTComponents(jwt)
            val historicalKeysResponse = context.json.decodeFromJsonElement(
                FederationHistoricalKeysResponse.serializer(),
                decodedJwt.payload
            )
            logger.debug("Successfully decoded historical keys response")
            IdkResult.ok(historicalKeysResponse.propertyKeys)
        } catch (e: Exception) {
            logger.error("Failed to decode historical keys response", e)
            IdkResult.err(InvalidEntityConfigurationError(entityId, "Failed to decode historical keys response: ${e.message}", e))
        }
    }
}
