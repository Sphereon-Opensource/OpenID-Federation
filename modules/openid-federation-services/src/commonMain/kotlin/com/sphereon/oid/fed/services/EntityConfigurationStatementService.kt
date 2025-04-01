package com.sphereon.oid.fed.services

import com.sphereon.crypto.kms.IKeyManagementSystem
import com.sphereon.oid.fed.common.builder.EntityConfigurationStatementObjectBuilder
import com.sphereon.oid.fed.common.builder.FederationEntityMetadataObjectBuilder
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.FederationEntityMetadata
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwtHeader
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.mappers.toJwk
import com.sphereon.oid.fed.services.mappers.toTrustMark
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement as EntityConfigurationStatementEntity

/**
 * Service responsible for managing entity configuration statements.
 * It provides functionality to generate, publish, and persist
 * entity configuration statements for a given account.
 *
 * @param accountService Service for handling account-specific operations.
 * @param jwkService Service to manage JSON Web Keys for accounts.
 * @param keyManagementSystem External system for signing and key management operations.
 */
class EntityConfigurationStatementService(
    private val accountService: AccountService,
    private val jwkService: JwkService,
    private val keyManagementSystem: IKeyManagementSystem
) {
    /**
     * Logger instance utilized for logging operations within the EntityConfigurationService class.
     * Tagged specifically as "EntityConfigurationService" to associate logged messages with this class's context.
     */
    private val logger = Logger.tag("EntityConfigurationService")
    /**
     * Reference to the persistence layer used for handling and querying entity configuration statements.
     * Acts as an entry point for database operations related to entity configuration entities within the service.
     */
    private val queries = Persistence

    /**
     * Companion object for the EntityConfigurationStatementService class.
     *
     * Provides constant and potentially shared utilities to support
     * the functionality of the enclosing class.
     */
    companion object {
        /**
         * The duration, in seconds, for which an entity configuration statement is considered valid.
         * Set to one year (3600 seconds * 24 hours * 365 days).
         */
        private const val EXPIRATION_PERIOD_SECONDS = 3600L * 24 * 365 // 1 year
    }

    /**
     * Retrieves the Entity Configuration Statement for a given account.
     *
     * @param account The account for which the entity configuration statement is to be retrieved.
     * @return The corresponding EntityConfigurationStatementEntity for the provided account.
     */
    fun findByAccount(account: Account): EntityConfigurationStatementEntity {
        logger.info("Finding entity configuration for account: ${account.username}")
        return getEntityConfigurationStatement(account)
    }

    /**
     * Publishes the entity configuration statement for the specified account.
     * Optionally supports a dry run mode where the resulting JWT is generated but not persisted.
     *
     * @param account The account for which the entity configuration statement is being published.
     * @param dryRun If true, the operation will simulate publishing without persisting the result. Defaults to false.
     * @return The JWT created for the entity configuration statement.
     * @throws IllegalArgumentException If the account does not have a valid key with a `kid` or required data is missing.
     */
    suspend fun publishByAccount(account: Account, dryRun: Boolean? = false, kmsKeyRef: String? = null, kid: String? = null): String {
        logger.info("Publishing entity configuration for account: ${account.username} (dryRun: $dryRun)")

        val entityConfigurationStatement = findByAccount(account)
        val keys = jwkService.getAssertedKeysForAccount(account, includeRevoked = false, kmsKeyRef = kmsKeyRef, kid = kid)
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

    /**
     * Builds the entity configuration statement for a given account.
     *
     * This method constructs an EntityConfigurationStatementEntity by obtaining the account's
     * identifier, keys, and adding required components to the base entity configuration statement.
     * It logs the process of building and completion of the entity configuration statement.
     *
     * @param account The account for which the entity configuration statement is being generated.
     *                The account must contain a username and relevant data to retrieve its identifier and keys.
     * @return The fully constructed and populated EntityConfigurationStatementEntity for the given account.
     */
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

    /**
     * Creates a base Entity Configuration Statement with specified parameters.
     *
     * @param identifier The unique identifier that acts as the issuer of the statement.
     * @param keys An array of JSON Web Keys (JWKs) to be included in the statement.
     * @return An instance of EntityConfigurationStatementObjectBuilder configured with the provided parameters.
     */
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

    /**
     * Adds various components to the provided `EntityConfigurationStatementObjectBuilder` for the given `account` and `identifier`.
     *
     * This method integrates additional metadata, authority hints, critical claims, trust mark issuers, and received trust marks
     * into the configuration statement builder to enrich the entity configuration statement.
     *
     * @param account The account for which the components are added.
     * @param builder The builder instance where the components will be added.
     * @param identifier The identifier associated with the account and configuration statement.
     */
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

    /**
     * Adds metadata for a federation entity to the provided builder based on the given account data
     * and identifier. This method checks whether the account has subordinates or issued trust marks,
     * and if so, constructs a federation entity metadata object and attaches it to the builder.
     *
     * @param account The account for which the federation entity metadata is being added.
     * @param builder The builder to which the federation entity metadata will be added.
     * @param identifier The unique identifier to be used for the federation entity metadata.
     */
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

    /**
     * Adds authority hints to the provided `EntityConfigurationStatementObjectBuilder` for the specified account.
     * This method retrieves the list of authority hints associated with the account and adds them to the builder.
     *
     * @param account The account whose authority hints will be fetched and added to the builder.
     * @param builder The builder to which the authority hints will be added.
     */
    private fun addAuthorityHints(account: Account, builder: EntityConfigurationStatementObjectBuilder) {
        queries.authorityHintQueries.findByAccountId(account.id)
            .executeAsList()
            .map { it.identifier }
            .forEach { builder.authorityHint(it) }
    }

    /**
     * Adds metadata associated with a given account to the specified entity configuration statement builder.
     *
     * @param account The account whose metadata needs to be added.
     * @param builder The builder to which the metadata will be added.
     */
    private fun addMetadata(account: Account, builder: EntityConfigurationStatementObjectBuilder) {
        queries.metadataQueries.findByAccountId(account.id)
            .executeAsList()
            .forEach {
                builder.metadata(Pair(it.key, Json.parseToJsonElement(it.metadata).jsonObject))
            }
    }

    /**
     * Adds critical claims associated with the given account to the provided builder.
     *
     * @param account The account whose associated critical claims are retrieved.
     * @param builder The EntityConfigurationStatementObjectBuilder to which the critical claims are added.
     */
    private fun addCrits(account: Account, builder: EntityConfigurationStatementObjectBuilder) {
        queries.critQueries.findByAccountId(account.id)
            .executeAsList()
            .map { it.claim }
            .forEach { builder.crit(it) }
    }

    /**
     * Adds trust mark issuers to the provided `EntityConfigurationStatementObjectBuilder`.
     *
     * This method retrieves trust mark types associated with the given account and, for each type,
     * finds the respective trust mark issuers. It then adds the trust mark types and their issuers
     * to the builder.
     *
     * @param account The account whose trust mark types and issuers are to be retrieved.
     * @param builder The builder object to which the trust mark types and issuers will be added.
     */
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

    /**
     * Adds received trust marks for the given account to the provided entity configuration statement builder.
     *
     * @param account The account for which the received trust marks are to be added.
     * @param builder The builder where the received trust marks will be appended.
     */
    private fun addReceivedTrustMarks(account: Account, builder: EntityConfigurationStatementObjectBuilder) {
        queries.receivedTrustMarkQueries.findByAccountId(account.id)
            .executeAsList()
            .forEach { receivedTrustMark ->
                builder.trustMark(receivedTrustMark.toTrustMark())
            }
    }



    /**
     * Creates a signed JWT using the provided entity configuration statement and key ID.
     *
     * @param statement The entity configuration statement to be signed.
     * @param keyId The unique identifier of the key to use for signing the JWT.
     * @return A signed JWT as a string.
     */
    private suspend fun createSignedJwt(statement: EntityConfigurationStatementEntity, keyId: String): String {
        val jwtService = JwtService(keyManagementSystem)
        val header = JwtHeader(typ = "entity-statement+jwt", kid = keyId)

        return jwtService.signSerializable(
            statement,
            header,
            keyId
        )
    }

    /**
     * Persists an entity configuration statement for a given account.
     *
     * @param account The account associated with the entity configuration statement.
     * @param statement The entity configuration statement to be persisted.
     * @param jwt The JSON Web Token representation of the statement.
     */
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
