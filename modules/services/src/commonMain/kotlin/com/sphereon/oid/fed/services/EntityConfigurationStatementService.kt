package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.builder.EntityConfigurationStatementBuilder
import com.sphereon.oid.fed.common.builder.FederationEntityMetadataBuilder
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.FederationEntityMetadata
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.extensions.toJwkDTO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class EntityConfigurationStatementService {
    private val accountService = AccountService()
    private val keyService = KeyService()
    private val entityConfigurationStatementQueries = Persistence.entityConfigurationStatementQueries
    private val accountQueries = Persistence.accountQueries
    private val subordinateQueries = Persistence.subordinateQueries
    private val authorityHintQueries = Persistence.authorityHintQueries

    fun findByUsername(accountUsername: String): EntityConfigurationStatement {
        val account = accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)
        val identifier = accountService.getAccountIdentifier(account.username)
        val keys = keyService.getKeys(accountUsername).map { it.toJwkDTO() }.toTypedArray()
        val hasSubordinates = subordinateQueries.findByAccountId(account.id).executeAsList().isNotEmpty()
        val authorityHints =
            authorityHintQueries.findByAccountId(account.id).executeAsList().map { it.identifier }.toTypedArray()
        val metadata = Persistence.entityConfigurationMetadataQueries.findByAccountId(account.id).executeAsList()

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

        authorityHints.forEach {
            entityConfigurationStatement.authorityHint(it)
        }

        metadata.forEach {
            entityConfigurationStatement.metadata(
                Pair(it.key, Json.parseToJsonElement(it.metadata).jsonObject)
            )
        }

        return entityConfigurationStatement.build()
    }

    fun publishByUsername(accountUsername: String): EntityConfigurationStatement {
        val account = accountService.getAccountByUsername(accountUsername)

        val entityConfigurationStatement = findByUsername(accountUsername)

        // @TO-DO JWT creation and signing

        entityConfigurationStatementQueries.create(
            account_id = account.id,
            expires_at = entityConfigurationStatement.exp.toLong(),
            statement = Json.encodeToString(EntityConfigurationStatement.serializer(), entityConfigurationStatement)
        ).executeAsOne()

        return entityConfigurationStatement
    }
}
