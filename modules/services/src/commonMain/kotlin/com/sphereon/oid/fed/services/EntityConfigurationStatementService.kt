package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.services.config.AccountConfig
import com.sphereon.oid.fed.common.builder.EntityConfigurationStatementObjectBuilder
import com.sphereon.oid.fed.common.builder.FederationEntityMetadataObjectBuilder
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.*
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.mappers.toJwk
import com.sphereon.oid.fed.services.mappers.toTrustMark
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class EntityConfigurationStatementService(
    private val accountService: AccountService,
    private val keyService: KeyService,
    private val kmsClient: KmsClient
) {
    private val logger = Logger.tag("EntityConfigurationStatementService")

    private fun getEntityConfigurationStatement(account: Account): EntityConfigurationStatementDTO {
        logger.info("Building entity configuration for account: ${account.username}")

        val identifier = accountService.getAccountIdentifierByAccount(account)
        val keys = keyService.getKeys(account)

        val entityConfigBuilder = createBaseEntityConfigurationStatement(identifier, keys)

        addOptionalMetadata(account, entityConfigBuilder, identifier)
        addAuthorityHints(account, entityConfigBuilder)
        addCustomMetadata(account, entityConfigBuilder)
        addCrits(account, entityConfigBuilder)
        addTrustMarkIssuers(account, entityConfigBuilder)
        addReceivedTrustMarks(account, entityConfigBuilder)

        logger.info("Successfully built entity configuration statement for account: ${account.username}")
        return entityConfigBuilder.build()
    }

    private fun createBaseEntityConfigurationStatement(
        identifier: String,
        keys: Array<JwkAdminDTO>
    ): EntityConfigurationStatementObjectBuilder {
        return EntityConfigurationStatementObjectBuilder()
            .iss(identifier)
            .iat((System.currentTimeMillis() / 1000).toInt())
            .exp((System.currentTimeMillis() / 1000 + 3600 * 24 * 365).toInt())
            .jwks(keys.map { it.toJwk() }.toMutableList())
    }

    private fun addOptionalMetadata(
        account: Account,
        builder: EntityConfigurationStatementObjectBuilder,
        identifier: String
    ) {
        val hasSubordinates = Persistence.subordinateQueries.findByAccountId(account.id).executeAsList().isNotEmpty()
        val issuedTrustMarks = Persistence.trustMarkQueries.findByAccountId(account.id).executeAsList().isNotEmpty()

        if (hasSubordinates || issuedTrustMarks) {
            val federationEntityMetadata = FederationEntityMetadataObjectBuilder()
                .identifier(identifier)
                .build()

            builder.metadata(
                Pair(
                    "federation_entity",
                    Json.encodeToJsonElement(FederationEntityMetadata.serializer(), federationEntityMetadata).jsonObject
                )
            )
        }
    }

    private fun addAuthorityHints(
        account: Account,
        builder: EntityConfigurationStatementObjectBuilder
    ) {
        Persistence.authorityHintQueries.findByAccountId(account.id)
            .executeAsList()
            .map { it.identifier }
            .forEach { builder.authorityHint(it) }
    }

    private fun addCustomMetadata(
        account: Account,
        builder: EntityConfigurationStatementObjectBuilder
    ) {
        Persistence.entityConfigurationMetadataQueries.findByAccountId(account.id)
            .executeAsList()
            .forEach {
                builder.metadata(
                    Pair(it.key, Json.parseToJsonElement(it.metadata).jsonObject)
                )
            }
    }

    private fun addCrits(
        account: Account,
        builder: EntityConfigurationStatementObjectBuilder
    ) {
        Persistence.critQueries.findByAccountId(account.id)
            .executeAsList()
            .map { it.claim }
            .forEach { builder.crit(it) }
    }

    private fun addTrustMarkIssuers(
        account: Account,
        builder: EntityConfigurationStatementObjectBuilder
    ) {
        Persistence.trustMarkTypeQueries.findByAccountId(account.id)
            .executeAsList()
            .forEach { trustMarkType ->
                val trustMarkIssuers = Persistence.trustMarkIssuerQueries
                    .findByTrustMarkTypeId(trustMarkType.id)
                    .executeAsList()

                builder.trustMarkIssuer(
                    trustMarkType.identifier,
                    trustMarkIssuers.map { it.issuer_identifier }
                )
            }
    }

    private fun addReceivedTrustMarks(
        account: Account,
        builder: EntityConfigurationStatementObjectBuilder
    ) {
        Persistence.receivedTrustMarkQueries.findByAccountId(account.id)
            .executeAsList()
            .forEach { receivedTrustMark ->
                builder.trustMark(receivedTrustMark.toTrustMark())
            }
    }

    fun findByAccount(account: Account): EntityConfigurationStatementDTO {
        logger.info("Finding entity configuration for account: ${account.username}")
        return getEntityConfigurationStatement(account)
    }

    fun publishByAccount(account: Account, dryRun: Boolean? = false): String {
        logger.info("Publishing entity configuration for account: ${account.username} (dryRun: $dryRun)")

        val entityConfigurationStatement = findByAccount(account)

        val keys = keyService.getKeys(account)

        if (keys.isEmpty()) {
            logger.error("No keys found for account: ${account.username}")
            throw IllegalArgumentException(Constants.NO_KEYS_FOUND)
        }

        val key = keys[0].kid

        val jwt = kmsClient.sign(
            payload = Json.encodeToJsonElement(
                EntityConfigurationStatementDTO.serializer(),
                entityConfigurationStatement
            ).jsonObject,
            header = JWTHeader(typ = "entity-statement+jwt", kid = key!!),
            keyId = key
        )

        if (dryRun == true) {
            logger.info("Dry run completed, returning JWT without persisting")
            return jwt
        }

        Persistence.entityConfigurationStatementQueries.create(
            account_id = account.id,
            expires_at = entityConfigurationStatement.exp.toLong(),
            statement = jwt
        ).executeAsOne()

        logger.info("Successfully published entity configuration statement for account: ${account.username}")
        return jwt
    }
}
