package com.sphereon.oid.fed.services

import com.sphereon.crypto.kms.IKeyManagementSystem
import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.builder.EntityConfigurationStatementObjectBuilder
import com.sphereon.oid.fed.common.builder.FederationEntityMetadataObjectBuilder
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.openapi.models.FederationEntityMetadata
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwtHeader
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.mappers.jwk.toJwk
import com.sphereon.oid.fed.services.mappers.trustMark.toTrustMark
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement as EntityConfigurationStatementEntity

class EntityConfigurationStatementService(
    private val accountService: AccountService,
    private val jwkService: JwkService,
    private val kmsProvider: IKeyManagementSystem
) {
    private val logger = Logger.tag("EntityConfigurationStatementService")
    private val queries = Persistence

    companion object {
        private const val EXPIRATION_PERIOD_SECONDS = 3600L * 24 * 365 // 1 year
    }

    fun findByAccount(account: Account): EntityConfigurationStatementEntity {
        logger.info("Finding entity configuration for account: ${account.username}")
        return getEntityConfigurationStatement(account)
    }

    suspend fun publishByAccount(account: Account, dryRun: Boolean? = false): String {
        logger.info("Publishing entity configuration for account: ${account.username} (dryRun: $dryRun)")

        val entityConfigurationStatement = findByAccount(account)
        val keys = getKeysOrThrow(account)
        val key = keys[0].kid ?: throw IllegalArgumentException("First key must have a kid")

        val jwt = createSignedJwt(entityConfigurationStatement, key)

        if (dryRun == true) {
            logger.info("Dry run completed, returning JWT without persisting")
            return jwt
        }

        persistEntityConfiguration(account, entityConfigurationStatement, jwt)
        logger.info("Successfully published entity configuration statement for account: ${account.username}")
        return jwt
    }

    private fun getEntityConfigurationStatement(account: Account): EntityConfigurationStatementEntity {
        logger.info("Building entity configuration for account: ${account.username}")

        val identifier = accountService.getAccountIdentifierByAccount(account)
        val keys = jwkService.getKeys(account)
        val entityConfigBuilder =
            createBaseEntityConfigurationStatement(identifier, keys.map { it.toJwk() }.toTypedArray())

        addComponents(account, entityConfigBuilder, identifier)

        logger.info("Successfully built entity configuration statement for account: ${account.username}")
        return entityConfigBuilder.build()
    }

    private fun createBaseEntityConfigurationStatement(
        identifier: String,
        keys: Array<Jwk>
    ): EntityConfigurationStatementObjectBuilder {
        val currentTimeSeconds = System.currentTimeMillis() / 1000
        return EntityConfigurationStatementObjectBuilder()
            .iss(identifier)
            .iat(currentTimeSeconds.toInt())
            .exp((currentTimeSeconds + EXPIRATION_PERIOD_SECONDS).toInt())
            .jwks(keys.toMutableList())
    }

    private fun addComponents(
        account: Account,
        builder: EntityConfigurationStatementObjectBuilder,
        identifier: String
    ) {
        addFederationEntityMetadata(account, builder, identifier)
        addMetadata(account, builder)
        addAuthorityHints(account, builder)
        addCrits(account, builder)
        addTrustMarkIssuers(account, builder)
        addReceivedTrustMarks(account, builder)
    }

    private fun addFederationEntityMetadata(
        account: Account,
        builder: EntityConfigurationStatementObjectBuilder,
        identifier: String
    ) {
        val hasSubordinates = queries.subordinateQueries.findByAccountId(account.id).executeAsList().isNotEmpty()
        val issuedTrustMarks = queries.trustMarkQueries.findByAccountId(account.id).executeAsList().isNotEmpty()

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

    private fun addAuthorityHints(account: Account, builder: EntityConfigurationStatementObjectBuilder) {
        queries.authorityHintQueries.findByAccountId(account.id)
            .executeAsList()
            .map { it.identifier }
            .forEach { builder.authorityHint(it) }
    }

    private fun addMetadata(account: Account, builder: EntityConfigurationStatementObjectBuilder) {
        queries.metadataQueries.findByAccountId(account.id)
            .executeAsList()
            .forEach {
                builder.metadata(Pair(it.key, Json.parseToJsonElement(it.metadata).jsonObject))
            }
    }

    private fun addCrits(account: Account, builder: EntityConfigurationStatementObjectBuilder) {
        queries.critQueries.findByAccountId(account.id)
            .executeAsList()
            .map { it.claim }
            .forEach { builder.crit(it) }
    }

    private fun addTrustMarkIssuers(account: Account, builder: EntityConfigurationStatementObjectBuilder) {
        queries.trustMarkTypeQueries.findByAccountId(account.id)
            .executeAsList()
            .forEach { trustMarkType ->
                val trustMarkIssuers = queries.trustMarkIssuerQueries
                    .findByTrustMarkTypeId(trustMarkType.id)
                    .executeAsList()

                builder.trustMarkIssuer(
                    trustMarkType.identifier,
                    trustMarkIssuers.map { it.issuer_identifier }
                )
            }
    }

    private fun addReceivedTrustMarks(account: Account, builder: EntityConfigurationStatementObjectBuilder) {
        queries.receivedTrustMarkQueries.findByAccountId(account.id)
            .executeAsList()
            .forEach { receivedTrustMark ->
                builder.trustMark(receivedTrustMark.toTrustMark())
            }
    }

    private fun getKeysOrThrow(account: Account): Array<AccountJwk> {
        return jwkService.getKeys(account).also {
            if (it.isEmpty()) {
                logger.error("No keys found for account: ${account.username}")
                throw IllegalArgumentException(Constants.NO_KEYS_FOUND)
            }
        }
    }

    private suspend fun createSignedJwt(statement: EntityConfigurationStatementEntity, keyId: String): String {
        val jwtService = JwtService(kmsProvider)
        val header = JwtHeader(typ = "entity-statement+jwt", kid = keyId)

        return jwtService.signSerializable(
            statement,
            header,
            keyId
        )
    }

    private fun persistEntityConfiguration(
        account: Account,
        statement: EntityConfigurationStatementEntity,
        jwt: String
    ) {
        queries.entityConfigurationStatementQueries.create(
            account_id = account.id,
            expires_at = statement.exp.toLong(),
            statement = jwt
        ).executeAsOne()
    }
}
