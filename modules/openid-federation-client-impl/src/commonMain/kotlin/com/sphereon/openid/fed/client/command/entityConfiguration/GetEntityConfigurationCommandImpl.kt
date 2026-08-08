package com.sphereon.openid.fed.client.command.entityConfiguration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.crypto.fetchAndVerifyJwt
import com.sphereon.openid.fed.client.crypto.verifyJwtSignature
import com.sphereon.openid.fed.client.helpers.getEntityConfigurationEndpoint
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.client.services.entityConfigurationStatementService.EntityConfigurationStatementServiceConst
import com.sphereon.openid.fed.core.error.EntityNotFoundError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidEntityConfigurationError
import com.sphereon.openid.fed.core.error.SignatureVerificationFailedError
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.Jwk
import kotlinx.serialization.json.jsonObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the GetEntityConfigurationCommand.
 * Fetches and validates entity configuration statements.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetEntityConfigurationCommand>())
class GetEntityConfigurationCommandImpl(
    execution: SessionExecution,
    private val context: FederationContext
) : ExecutionScopedCommandAdapter<GetEntityConfigurationArgs, EntityConfigurationStatement, FederationError>(
    id = GetEntityConfigurationCommand.COMMAND_ID,
    execution = execution
), GetEntityConfigurationCommand {

    private val logger = EntityConfigurationStatementServiceConst.LOG

    override suspend fun getEntityConfiguration(
        entityIdentifier: String
    ): IdkResult<EntityConfigurationStatement, FederationError> {
        return execute(
            GetEntityConfigurationArgs(entityIdentifier)
        )
    }

    override suspend fun doExecute(
        args: GetEntityConfigurationArgs,
        applyDuring: (GetEntityConfigurationArgs) -> GetEntityConfigurationArgs
    ): IdkResult<EntityConfigurationStatement, FederationError> {
        val (entityIdentifier) = applyDuring(args)

        logger.info("Starting entity configuration resolution for: $entityIdentifier")

        val endpoint = getEntityConfigurationEndpoint(entityIdentifier)
        logger.debug("Generated endpoint URL: $endpoint")

        return try {
            // Fetch the JWT
            val jwt = context.jwtService.fetchAndVerifyJwt(endpoint, context.httpResolver)
            val decodedJwt = decodeJWTComponents(jwt)

            // Verify the self-signed JWT using its embedded JWKS
            val jwksJson = decodedJwt.payload["jwks"]?.jsonObject
                ?: return IdkResult.err(InvalidEntityConfigurationError(entityIdentifier, "No JWKS found in JWT payload"))

            val keysJsonArray = jwksJson["keys"].toString()
            val jwks: Array<Jwk> = context.json.decodeFromString(keysJsonArray)
            val key = jwks.find { it.kid == decodedJwt.header.kid }
                ?: return IdkResult.err(InvalidEntityConfigurationError(entityIdentifier, "No matching key found for kid: ${decodedJwt.header.kid}"))

            if (!context.jwtService.verifyJwtSignature(jwt, key)) {
                return IdkResult.err(SignatureVerificationFailedError("Entity configuration JWT signature verification failed"))
            }

            logger.debug("Decoding JWT payload into EntityConfigurationStatement")
            val result: EntityConfigurationStatement = context.json.decodeFromString(decodedJwt.payload.toString())
            logger.info("Successfully resolved entity configuration for: $entityIdentifier")
            IdkResult.ok(result)
        } catch (e: Exception) {
            logger.error("Failed to fetch entity configuration", e)
            IdkResult.err(EntityNotFoundError(entityIdentifier, e))
        }
    }
}
