package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.builder.EntityConfigurationStatementBuilder
import com.sphereon.oid.fed.common.builder.FederationEntityMetadataBuilder
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.FederationEntityMetadata
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.extensions.toJwk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class EntityConfigurationStatementService {
    private val accountService = AccountService()
    private val keyService = KeyService()
    private val kmsClient = KmsService.getKmsClient()
    private val entityConfigurationStatementQueries = Persistence.entityConfigurationStatementQueries
    private val accountQueries = Persistence.accountQueries
    private val subordinateQueries = Persistence.subordinateQueries
    private val authorityHintQueries = Persistence.authorityHintQueries

    fun findByUsername(accountUsername: String): EntityConfigurationStatement {
        val account = accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)

        val identifier = accountService.getAccountIdentifier(account.username)
        val keys = keyService.getKeys(accountUsername)
        val hasSubordinates = subordinateQueries.findByAccountId(account.id).executeAsList().isNotEmpty()
        val authorityHints =
            authorityHintQueries.findByAccountId(account.id).executeAsList().map { it.identifier }.toTypedArray()
        val crits = Persistence.critQueries.findByAccountId(account.id).executeAsList().map { it.claim }.toTypedArray()
        val metadata = Persistence.entityConfigurationMetadataQueries.findByAccountId(account.id).executeAsList()

        val entityConfigurationStatement = EntityConfigurationStatementBuilder()
            .iss(identifier)
            .iat((System.currentTimeMillis() / 1000).toInt())
            .exp((System.currentTimeMillis() / 1000 + 3600 * 24 * 365).toInt())
            .jwks(keys.map { it.toJwk() }.toMutableList())

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

        crits.forEach {
            entityConfigurationStatement.crit(it)
        }

        return entityConfigurationStatement.build()
    }

    fun publishByUsername(accountUsername: String, dryRun: Boolean? = false): String {
        val account = accountService.getAccountByUsername(accountUsername)

        val entityConfigurationStatement = findByUsername(accountUsername)

        val keys = keyService.getKeys(accountUsername)

        if (keys.isEmpty()) {
            throw IllegalArgumentException(Constants.NO_KEYS_FOUND)
        }

        val key = keys[0].kid

        val jwt = kmsClient.sign(
            payload = Json.encodeToJsonElement(
                EntityConfigurationStatement.serializer(),
                entityConfigurationStatement
            ).jsonObject,
            header = JWTHeader(typ = "entity-statement+jwt", kid = key!!),
            keyId = key
        )

        if (dryRun == true) {
            return jwt
        }

        entityConfigurationStatementQueries.create(
            account_id = account.id,
            expires_at = entityConfigurationStatement.exp.toLong(),
            statement = jwt
        ).executeAsOne()

        return jwt
    }
}
