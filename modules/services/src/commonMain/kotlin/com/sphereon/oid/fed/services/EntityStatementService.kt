package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.builder.EntityConfigurationStatementBuilder
import com.sphereon.oid.fed.common.builder.FederationEntityMetadataBuilder
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.FederationEntityMetadata
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.extensions.toJwkDTO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class EntityStatementService {
    private val accountService = AccountService()
    private val keyService = KeyService()
    private val subordinateService = SubordinateService()
    private val entityConfigurationStatementQueries = Persistence.entityConfigurationStatementQueries

    fun findByUsername(accountUsername: String): EntityConfigurationStatement {
        val account = accountService.getAccountByUsername(accountUsername)

        val keys = keyService.getKeys(accountUsername).map { it.toJwkDTO() }.toTypedArray()

        val hasSubordinates = subordinateService.findSubordinatesByAccount(accountUsername).isNotEmpty()

        val identifier = accountService.getAccountIdentifier(account.username)

        val entityConfigurationStatement = EntityConfigurationStatementBuilder()
            .iss(identifier)
            .iat((System.currentTimeMillis() / 1000).toInt())
            .exp((System.currentTimeMillis() / 1000 + 3600 * 24 * 365).toInt())
            .jwks(keys)

        if (hasSubordinates) {
            val federationEntityMetadata = FederationEntityMetadataBuilder()
                .identifier(identifier)
                .build()

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
        val account = accountService.getAccountByUsername(accountUsername)

        // fetching
        val entityConfigurationStatement = findByUsername(accountUsername)
        // signing

        // publishing
        entityConfigurationStatementQueries.create(
            account_id = account.id,
            expires_at = entityConfigurationStatement.exp.toLong(),
            statement = Json.encodeToString(EntityConfigurationStatement.serializer(), entityConfigurationStatement)
        )

        throw UnsupportedOperationException("Not implemented")
    }
}