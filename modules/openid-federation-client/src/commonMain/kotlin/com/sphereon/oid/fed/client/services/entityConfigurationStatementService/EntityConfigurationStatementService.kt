package com.sphereon.oid.fed.client.services.entityConfigurationStatementService

import com.sphereon.oid.fed.client.crypto.cryptoService
import com.sphereon.oid.fed.client.helpers.findKeyInJwks
import com.sphereon.oid.fed.client.helpers.getEntityConfigurationEndpoint
import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.client.types.ICryptoService
import com.sphereon.oid.fed.client.types.IFetchService
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatementDTO
import com.sphereon.oid.fed.openapi.models.FederationEntityMetadata
import com.sphereon.oid.fed.openapi.models.JWT
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private val logger: Logger = EntityConfigurationStatemenServiceConst.LOG

class EntityConfigurationStatementService(
    private val fetchService: IFetchService,
    private val cryptoService: ICryptoService = cryptoService(),
) {

    /**
     * Resolves and fetches an Entity Configuration Statement for the given entity identifier.
     *
     * @param entityIdentifier The entity identifier for which to get the statement.
     * @return [JWT] A JWT object containing the entity configuration statement.
     * @throws IllegalStateException if the JWT is invalid or signature verification fails
     */
    suspend fun getEntityConfigurationStatement(entityIdentifier: String): EntityConfigurationStatementDTO {
        logger.info("Starting entity configuration resolution for: $entityIdentifier")

        val endpoint = getEntityConfigurationEndpoint(entityIdentifier)
        logger.debug("Generated endpoint URL: $endpoint")

        logger.debug("Fetching JWT from endpoint")
        val jwt = fetchService.fetchStatement(endpoint)
        logger.debug("Successfully fetched JWT")

        logger.debug("Decoding JWT components")
        val decodedJwt = decodeJWTComponents(jwt)
        logger.debug("JWT decoded successfully. Header kid: ${decodedJwt.header.kid}")

        // Verify the JWT is self-signed using its own JWKS
        logger.debug("Extracting JWKS from payload")
        val jwks = decodedJwt.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray
            ?: run {
                logger.error("No JWKS found in entity configuration")
                throw IllegalStateException("No JWKS found in entity configuration")
            }

        logger.debug("Finding matching key in JWKS for kid: ${decodedJwt.header.kid}")
        val key = findKeyInJwks(jwks, decodedJwt.header.kid)
            ?: run {
                logger.error("No matching key found for kid: ${decodedJwt.header.kid}")
                throw IllegalStateException("No matching key found for kid: ${decodedJwt.header.kid}")
            }

        logger.debug("Verifying JWT signature")
        if (!cryptoService.verify(jwt, key)) {
            logger.error("Entity configuration signature verification failed")
            throw IllegalStateException("Entity configuration signature verification failed")
        }
        logger.debug("JWT signature verified successfully")

        return try {
            logger.debug("Creating JSON decoder with relaxed settings")
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            }

            logger.debug("Decoding JWT payload into EntityConfigurationStatementDTO")
            val result = json.decodeFromJsonElement(
                EntityConfigurationStatementDTO.serializer(),
                decodedJwt.payload
            )
            logger.info("Successfully resolved entity configuration for: $entityIdentifier")
            result
        } catch (e: Exception) {
            logger.error("Failed to decode entity configuration", e)
            throw IllegalStateException("Failed to decode entity configuration: ${e.message}", e)
        }
    }
}

fun EntityConfigurationStatementDTO.getFederationEndpoints(): FederationEntityMetadata {
    logger.debug("Extracting federation endpoints from EntityConfigurationStatementDTO")

    // Check if metadata exists
    val metadata = this.metadata
        ?: run {
            logger.error("No metadata found in entity configuration")
            throw IllegalStateException("No metadata found in entity configuration")
        }

    // Extract the federation_entity metadata from the metadata object
    logger.debug("Extracting federation_entity metadata")
    val federationMetadata = metadata["federation_entity"]?.jsonObject
        ?: run {
            logger.error("No federation_entity metadata found in entity configuration")
            throw IllegalStateException("No federation_entity metadata found in entity configuration")
        }

    // Deserialize the federation metadata into our DTO
    return try {
        logger.debug("Creating JSON decoder for federation metadata")
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        logger.debug("Decoding federation metadata into FederationEntityMetadata")
        val result = json.decodeFromJsonElement(
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