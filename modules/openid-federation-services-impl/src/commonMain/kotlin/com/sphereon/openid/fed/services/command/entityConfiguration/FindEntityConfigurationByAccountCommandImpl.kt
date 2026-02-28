package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.builder.EntityConfigurationStatementObjectBuilder
import com.sphereon.openid.fed.common.builder.FederationEntityMetadataObjectBuilder
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.FederationEntityMetadata
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.AccountService
import com.sphereon.openid.fed.services.JwkService
import com.sphereon.openid.fed.services.mappers.toJwk
import com.sphereon.openid.fed.services.mappers.toTrustMark
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the FindEntityConfigurationByAccountCommand.
 * Retrieves the entity configuration statement for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FindEntityConfigurationByAccountCommand::class)
class FindEntityConfigurationByAccountCommandImpl(
    execution: SessionExecution,
    private val accountService: AccountService,
    private val jwkService: JwkService
) : ExecutionScopedCommandAdapter<FindEntityConfigurationByAccountArgs, EntityConfigurationStatement, FederationError>(
    id = FindEntityConfigurationByAccountCommand.COMMAND_ID,
    execution = execution
), FindEntityConfigurationByAccountCommand {

    private val logger = Log.app().withTag("FindEntityConfigurationByAccountCommand")
    private val queries = Persistence

    companion object {
        private const val EXPIRATION_PERIOD_SECONDS = 3600L * 24 * 365
    }

    override suspend fun findByAccount(account: Account): IdkResult<EntityConfigurationStatement, FederationError> {
        return execute(FindEntityConfigurationByAccountArgs(account))
    }

    override suspend fun doExecute(
        args: FindEntityConfigurationByAccountArgs,
        applyDuring: (FindEntityConfigurationByAccountArgs) -> FindEntityConfigurationByAccountArgs
    ): IdkResult<EntityConfigurationStatement, FederationError> {
        val (account) = applyDuring(args)

        logger.info("Finding entity configuration for account: ${account.username}")

        return getEntityConfigurationStatement(account)
    }

    private suspend fun getEntityConfigurationStatement(account: Account): IdkResult<EntityConfigurationStatement, FederationError> {
        logger.info("Building entity configuration for account: ${account.username}")

        val identifierResult = accountService.getAccountIdentifierByAccount(account)
        if (identifierResult.isErr) {
            return identifierResult.error.asErrorResult()
        }
        val identifier = identifierResult.value

        val keysResult = jwkService.getKeys(account, includeRevoked = false)
        if (keysResult.isErr) {
            return keysResult.error.asErrorResult()
        }
        val keys = keysResult.value

        return try {
            val entityConfigBuilder = createBaseEntityConfigurationStatement(
                identifier,
                keys.map { it.toJwk() }.toTypedArray()
            )

            addComponents(account, entityConfigBuilder, identifier)

            logger.info("Successfully built entity configuration statement for account: ${account.username}")
            IdkResult.ok(entityConfigBuilder.build())
        } catch (e: Exception) {
            logger.error("Failed to build entity configuration for account: ${account.username}", e)
            IdkResult.err(ServerError("Failed to build entity configuration", e.message, e))
        }
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
        addMetadataPolicy(account, builder)
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

    private fun addMetadataPolicy(account: Account, builder: EntityConfigurationStatementObjectBuilder) {
        queries.metadataPolicyQueries.findByAccountId(account.id)
            .executeAsList()
            .forEach {
                builder.metadataPolicy(Pair(it.key, Json.parseToJsonElement(it.policy).jsonObject))
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
}
