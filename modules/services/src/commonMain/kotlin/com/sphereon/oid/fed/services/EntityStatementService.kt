package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.builder.EntityConfigurationStatementBuilder
import com.sphereon.oid.fed.common.builder.FederationEntityMetadataBuilder
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.FederationEntityMetadata
import com.sphereon.oid.fed.services.extensions.toJwkDTO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class EntityStatementService {
    private val keyService = KeyService()
    private val subordinateService = SubordinateService()

    fun findByUsername(accountUsername: String): EntityConfigurationStatement {
        val keys = keyService.getKeys(accountUsername).map { it.toJwkDTO() }.toTypedArray()

        val hasSubordinates = subordinateService.findSubordinatesByAccount(accountUsername).isNotEmpty()
        println(hasSubordinates);

        val entityConfigurationStatement = EntityConfigurationStatementBuilder()
            .iss("https://www.sphereon.com")
            .iat((System.currentTimeMillis() / 1000).toInt())
            .exp((System.currentTimeMillis() / 1000 + 3600 * 24 * 365).toInt())
            .jwks(keys)

        if (hasSubordinates) {
            val federationEntityMetadata = FederationEntityMetadataBuilder()
                .identifier(accountUsername)
                .build()

            println(federationEntityMetadata);

            entityConfigurationStatement.metadata(
                Pair(
                    "federation_entity",
                    Json.encodeToJsonElement(FederationEntityMetadata.serializer(), federationEntityMetadata).jsonObject
                )
            )
        }

        return entityConfigurationStatement.build()
    }

    fun publishByUsername(accountUsername: String): EntityConfigurationStatement {
        // fetching
        // signing
        // publishing
        throw UnsupportedOperationException("Not implemented")
    }
}