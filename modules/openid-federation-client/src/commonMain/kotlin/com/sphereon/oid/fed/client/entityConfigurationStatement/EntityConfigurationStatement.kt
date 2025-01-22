package com.sphereon.oid.fed.client.entityConfigurationStatement

import com.sphereon.oid.fed.client.crypto.ICryptoService
import com.sphereon.oid.fed.client.crypto.cryptoService
import com.sphereon.oid.fed.client.fetch.IFetchService
import com.sphereon.oid.fed.client.helpers.findKeyInJwks
import com.sphereon.oid.fed.client.helpers.getEntityConfigurationEndpoint
import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatementDTO
import com.sphereon.oid.fed.openapi.models.FederationEntityMetadata
import com.sphereon.oid.fed.openapi.models.JWT
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class EntityConfigurationStatement(
    private val fetchService: IFetchService,
    private val cryptoService: ICryptoService = cryptoService()
) {
    /**
     * Resolves and fetches an Entity Configuration Statement for the given entity identifier.
     *
     * @param entityIdentifier The entity identifier for which to get the statement.
     * @return [JWT] A JWT object containing the entity configuration statement.
     * @throws IllegalStateException if the JWT is invalid or signature verification fails
     */
    suspend fun getEntityConfigurationStatement(entityIdentifier: String): EntityConfigurationStatementDTO {
        EntityConfigurationStatementConst.LOG.debug("Resolving entity configuration for: $entityIdentifier")

        val endpoint = getEntityConfigurationEndpoint(entityIdentifier)
        EntityConfigurationStatementConst.LOG.debug("Fetching from endpoint: $endpoint")

        val jwt = fetchService.fetchStatement(endpoint)
        val decodedJwt = decodeJWTComponents(jwt)

        // Verify the JWT is self-signed using its own JWKS
        val key = findKeyInJwks(
            decodedJwt.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray
                ?: throw IllegalStateException("No JWKS found in entity configuration"),
            decodedJwt.header.kid
        ) ?: throw IllegalStateException("No matching key found for kid: ${decodedJwt.header.kid}")

        if (!cryptoService.verify(jwt, key)) {
            throw IllegalStateException("Entity configuration signature verification failed")
        }

        return try {
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            }
            json.decodeFromJsonElement(
                EntityConfigurationStatementDTO.serializer(),
                decodedJwt.payload
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to decode entity configuration: ${e.message}", e)
        }
    }
}

fun EntityConfigurationStatementDTO.getFederationEndpoints(): FederationEntityMetadata {
    // Check if metadata exists
    val metadata = this.metadata
        ?: throw IllegalStateException("No metadata found in entity configuration")

    // Extract the federation_entity metadata from the metadata object
    val federationMetadata = metadata["federation_entity"]?.jsonObject
        ?: throw IllegalStateException("No federation_entity metadata found in entity configuration")

    // Deserialize the federation metadata into our DTO
    return try {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        json.decodeFromJsonElement(
            FederationEntityMetadata.serializer(),
            federationMetadata
        )
    } catch (e: Exception) {
        throw IllegalStateException("Failed to parse federation_entity metadata: ${e.message}", e)
    }
}