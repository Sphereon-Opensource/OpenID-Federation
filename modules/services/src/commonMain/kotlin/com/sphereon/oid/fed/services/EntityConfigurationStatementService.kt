package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.builder.EntityConfigurationStatementObjectBuilder
import com.sphereon.oid.fed.common.builder.FederationEntityMetadataObjectBuilder
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.FederationEntityMetadata
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.mappers.toJwk
import com.sphereon.oid.fed.services.mappers.toTrustMark
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class EntityConfigurationStatementService {
    private val logger = Logger.tag("EntityConfigurationStatementService")
    private val accountService = AccountService()
    private val keyService = KeyService()
    private val kmsClient = KmsService.getKmsClient()

    fun findByUsername(accountUsername: String): EntityConfigurationStatement {
        logger.info("Finding entity configuration for username: $accountUsername")
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND).also {
                logger.error("Account not found for username: $accountUsername")
            }

        logger.debug("Found account with ID: ${account.id}")
        val identifier = accountService.getAccountIdentifier(account.username)
        val keys = keyService.getKeys(account.id)
        logger.debug("Retrieved ${keys.size} keys for account")
        val hasSubordinates = Persistence.subordinateQueries.findByAccountId(account.id).executeAsList().isNotEmpty()
        val issuedTrustMarks = Persistence.trustMarkQueries.findByAccountId(account.id).executeAsList().isNotEmpty()
        val authorityHints =
            Persistence.authorityHintQueries.findByAccountId(account.id).executeAsList().map { it.identifier }
                .toTypedArray()
        val crits = Persistence.critQueries.findByAccountId(account.id).executeAsList().map { it.claim }.toTypedArray()
        val metadata = Persistence.entityConfigurationMetadataQueries.findByAccountId(account.id).executeAsList()
        val trustMarkTypes =
            Persistence.trustMarkTypeQueries.findByAccountId(account.id).executeAsList()
        val receivedTrustMarks =
            Persistence.receivedTrustMarkQueries.findByAccountId(account.id).executeAsList()

        val entityConfigurationStatement = EntityConfigurationStatementObjectBuilder()
            .iss(identifier)
            .iat((System.currentTimeMillis() / 1000).toInt())
            .exp((System.currentTimeMillis() / 1000 + 3600 * 24 * 365).toInt())
            .jwks(keys.map { it.toJwk() }.toMutableList())

        if (hasSubordinates || issuedTrustMarks) {
            val federationEntityMetadata = FederationEntityMetadataObjectBuilder()
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

        trustMarkTypes.forEach { trustMarkType ->

            val trustMarkIssuers =
                Persistence.trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkType.id).executeAsList()

            entityConfigurationStatement.trustMarkIssuer(
                trustMarkType.identifier,
                trustMarkIssuers.map { it.issuer_identifier }
            )
        }

        receivedTrustMarks.forEach { receivedTrustMark ->
            entityConfigurationStatement.trustMark(receivedTrustMark.toTrustMark())
        }

        logger.info("Successfully built entity configuration statement for username: $accountUsername")
        return entityConfigurationStatement.build()
    }

    fun publishByUsername(accountUsername: String, dryRun: Boolean? = false): String {
        logger.info("Publishing entity configuration for username: $accountUsername (dryRun: $dryRun)")
        val account = accountService.getAccountByUsername(accountUsername)

        val entityConfigurationStatement = findByUsername(accountUsername)

        val keys = keyService.getKeys(account.id)

        if (keys.isEmpty()) {
            logger.error("No keys found for account: $accountUsername")
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
            logger.info("Dry run completed, returning JWT without persisting")
            return jwt
        }

        Persistence.entityConfigurationStatementQueries.create(
            account_id = account.id,
            expires_at = entityConfigurationStatement.exp.toLong(),
            statement = jwt
        ).executeAsOne()

        logger.info("Successfully published entity configuration statement for username: $accountUsername")
        return jwt
    }
}
