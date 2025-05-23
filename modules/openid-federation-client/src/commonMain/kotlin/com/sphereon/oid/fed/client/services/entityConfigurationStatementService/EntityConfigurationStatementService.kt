package com.sphereon.oid.fed.client.services.entityConfigurationStatementService

import com.sphereon.oid.fed.client.context.FederationContext
import com.sphereon.oid.fed.client.helpers.getEntityConfigurationEndpoint
import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.*
import kotlinx.serialization.json.jsonObject

private val logger: Logger = EntityConfigurationStatemenServiceConst.LOG

class EntityConfigurationStatementService(
    private val context: FederationContext
) {
    /**
     * Resolves and fetches an Entity Configuration Statement for the given entity identifier.
     *
     * @param entityIdentifier The entity identifier for which to get the statement.
     * @return [JWT] A JWT object containing the entity configuration statement.
     * @throws IllegalStateException if the JWT is invalid or signature verification fails
     */
    suspend fun fetchEntityConfigurationStatement(entityIdentifier: String): EntityConfigurationStatement {
        logger.info("Starting entity configuration resolution for: $entityIdentifier")

        val endpoint = getEntityConfigurationEndpoint(entityIdentifier)
        logger.debug("Generated endpoint URL: $endpoint")

        // Fetch and verify the JWT is self-signed
        val jwt = context.jwtService.fetchAndVerifyJwt(endpoint)
        val decodedJwt = decodeJWTComponents(jwt)
        context.jwtService.verifySelfSignedJwt(jwt)

        return try {
            logger.debug("Decoding JWT payload into EntityConfigurationStatement")
            val result: EntityConfigurationStatement = context.json.decodeFromString(decodedJwt.payload.toString())
            logger.info("Successfully resolved entity configuration for: $entityIdentifier")
            result
        } catch (e: Exception) {
            logger.error("Failed to decode entity configuration", e)
            throw IllegalStateException("Failed to decode entity configuration: ${e.message}", e)
        }
    }

    /**
     * Gets federation endpoints from an EntityConfigurationStatement
     */
    fun getFederationEndpoints(dto: EntityConfigurationStatement): FederationEntityMetadata {
        logger.debug("Extracting federation endpoints from EntityConfigurationStatement")

        val metadata = dto.metadata
            ?: run {
                logger.error("No metadata found in entity configuration")
                throw IllegalStateException("No metadata found in entity configuration")
            }

        val federationMetadata = metadata["federation_entity"]?.jsonObject
            ?: run {
                logger.error("No federation_entity metadata found in entity configuration")
                throw IllegalStateException("No federation_entity metadata found in entity configuration")
            }

        return try {
            logger.debug("Decoding federation metadata into FederationEntityMetadata")
            val result = context.json.decodeFromJsonElement(
                FederationEntityMetadata.serializer(),
                federationMetadata
            )
            logger.debug("Successfully extracted federation endpoints")
            result
        } catch (e: Exception) {
            logger.error("Failed to parse federation_entity metadata", e)
            throw IllegalStateException("Failed to parse federation_entity metadata: ${e.message}", e)
        }
    }

    /**
     * Retrieves the historical keys from the federation entity's historical keys endpoint.
     */
    suspend fun getHistoricalKeys(statement: EntityConfigurationStatement): List<HistoricalKey> {
        logger.debug("Retrieving historical keys")
        val historicalKeysJwt = fetchHistoricalKeysJwt(statement)
        val verifiedJwt = verifyHistoricalKeysJwt(statement, historicalKeysJwt)
        return decodeHistoricalKeys(verifiedJwt)
    }

    /**
     * Fetches the historical keys JWT from the federation endpoint
     */
    private suspend fun fetchHistoricalKeysJwt(dto: EntityConfigurationStatement): String {
        val federationEndpoints = getFederationEndpoints(dto)
        val historicalKeysEndpoint = federationEndpoints.federationHistoricalKeysEndpoint
            ?: run {
                logger.error("No historical keys endpoint found in federation metadata")
                throw IllegalStateException("No historical keys endpoint found in federation metadata")
            }

        logger.debug("Fetching historical keys from endpoint: $historicalKeysEndpoint")
        return try {
            val jwt = context.jwtService.fetchAndVerifyJwt(historicalKeysEndpoint)
            logger.debug("Successfully fetched historical keys JWT")
            jwt
        } catch (e: Exception) {
            logger.error("Failed to fetch historical keys", e)
            throw IllegalStateException("Failed to fetch historical keys: ${e.message}", e)
        }
    }

    /**
     * Verifies the historical keys JWT signature using the entity's current JWKS
     */
    private suspend fun verifyHistoricalKeysJwt(dto: EntityConfigurationStatement, jwt: String): String {
        val decodedJwt = decodeJWTComponents(jwt)
        logger.debug("Successfully decoded historical keys JWT")

        val signingKey = dto.jwks.propertyKeys?.find { it.kid == decodedJwt.header.kid }
            ?: run {
                logger.error("No matching key found for kid: ${decodedJwt.header.kid}")
                throw IllegalStateException("No matching key found for kid: ${decodedJwt.header.kid}")
            }

        context.jwtService.verifyJwt(jwt, signingKey)
        return jwt
    }

    /**
     * Decodes the JWT payload into an array of historical keys
     */
    private fun decodeHistoricalKeys(jwt: String): List<HistoricalKey> {
        return try {
            val decodedJwt = decodeJWTComponents(jwt)
            val historicalKeysResponse = context.json.decodeFromJsonElement(
                FederationHistoricalKeysResponse.serializer(),
                decodedJwt.payload
            )
            logger.debug("Successfully decoded historical keys response")
            historicalKeysResponse.propertyKeys
        } catch (e: Exception) {
            logger.error("Failed to decode historical keys response", e)
            throw IllegalStateException("Failed to decode historical keys response: ${e.message}", e)
        }
    }
}
