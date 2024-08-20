package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.builder.EntityConfigurationStatementBuilder
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.services.extensions.toJwkDTO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class EntityStatementService {
    private val keyService = KeyService()

    fun findByUsername(accountUsername: String): EntityConfigurationStatement {
        val metadata = Pair(
            "federation_entity", Json.parseToJsonElement(
                "{\n" +
                        "      \"federation_fetch_endpoint\": \"https://www.sphereon.com/fetch\",\n" +
                        "      \"federation_resolve_endpoint\": \"https://www.sphereon.com/resolve\",\n" +
                        "      \"federation_list_endpoint\": \"https://www.sphereon.com/list\"\n" +
                        "  }"
            ).jsonObject
        )

        val keys = keyService.getKeys(accountUsername).map { it.toJwkDTO() }.toTypedArray()

        val entityConfigurationStatement = EntityConfigurationStatementBuilder()
            .iss("https://www.sphereon.com")
            .iat((System.currentTimeMillis() / 1000).toInt())
            .exp((System.currentTimeMillis() / 1000 + 3600 * 24 * 365).toInt())
            .metadata(
                metadata
            )
            .jwks(keys)
            .build()

        return entityConfigurationStatement
    }

    fun publishByUsername(accountUsername: String): EntityConfigurationStatement {
        // fetching
        // signing
        // publishing
        throw UnsupportedOperationException("Not implemented")
    }
}