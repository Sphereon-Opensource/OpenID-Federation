package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.builder.EntityConfigurationStatementObjectBuilder
import com.sphereon.openid.fed.common.builder.FederationEntityMetadataObjectBuilder
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.TenantNotFoundError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.error.toIdkErrorResult
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.FederationEntityMetadata
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.persistence.Persistence
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
    private val tenantContextResolver: TenantContextResolver,
    private val jwkService: JwkService
) : TypedServiceCommandAdapter<FindEntityConfigurationByAccountArgs, EntityConfigurationStatement>(
    commandId = FindEntityConfigurationByAccountCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<FindEntityConfigurationByAccountArgs>(),
    outputTypeToken = typeToken<EntityConfigurationStatement>()
), FindEntityConfigurationByAccountCommand {

    private val logger = Log.app().withTag("FindEntityConfigurationByAccountCommand")
    private val queries = Persistence

    companion object {
        private const val EXPIRATION_PERIOD_SECONDS = 3600L * 24 * 365
    }

    override suspend fun doExecute(
        args: FindEntityConfigurationByAccountArgs,
        applyDuring: (FindEntityConfigurationByAccountArgs) -> FindEntityConfigurationByAccountArgs
    ): IdkResult<EntityConfigurationStatement, IdkError> {
        val (tenantId) = applyDuring(args)

        logger.info("Finding entity configuration for account: $tenantId")

        return getEntityConfigurationStatement(tenantId)
    }

    private suspend fun getEntityConfigurationStatement(tenantId: String): IdkResult<EntityConfigurationStatement, IdkError> {
        logger.info("Building entity configuration for account: $tenantId")

        val identifier = tenantContextResolver.resolveIdentifier(tenantId)
            ?: return federationErr(TenantNotFoundError(tenantId))

        val keysResult = jwkService.getKeys(tenantId, includeRevoked = false).toIdkErrorResult()
        if (keysResult.isErr) {
            return keysResult.error.asErrorResult()
        }
        val keys = keysResult.value

        return try {
            val entityConfigBuilder = createBaseEntityConfigurationStatement(
                identifier,
                keys.map { it.toJwk() }.toTypedArray()
            )

            addComponents(tenantId, entityConfigBuilder, identifier)

            logger.info("Successfully built entity configuration statement for account: $tenantId")
            IdkResult.ok(entityConfigBuilder.build())
        } catch (e: Exception) {
            logger.error("Failed to build entity configuration for account: $tenantId", e)
            federationErr(ServerError("Failed to build entity configuration", e.message, e))
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
        tenantId: String,
        builder: EntityConfigurationStatementObjectBuilder,
        identifier: String
    ) {
        addFederationEntityMetadata(tenantId, builder, identifier)
        addMetadata(tenantId, builder)
        addMetadataPolicy(tenantId, builder)
        addAuthorityHints(tenantId, builder)
        addTrustAnchorHints(tenantId, builder)
        addCrits(tenantId, builder)
        addTrustMarkIssuers(tenantId, builder)
        addReceivedTrustMarks(tenantId, builder)
    }

    private fun addFederationEntityMetadata(
        tenantId: String,
        builder: EntityConfigurationStatementObjectBuilder,
        identifier: String
    ) {
        val hasSubordinates = queries.subordinateQueries.findByAccountId(tenantId).executeAsList().isNotEmpty()
        val issuedTrustMarks = queries.trustMarkQueries.findByAccountId(tenantId).executeAsList().isNotEmpty()

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

    private fun addAuthorityHints(tenantId: String, builder: EntityConfigurationStatementObjectBuilder) {
        queries.authorityHintQueries.findByAccountId(tenantId)
            .executeAsList()
            .map { it.identifier }
            .forEach { builder.authorityHint(it) }
    }

    private fun addTrustAnchorHints(tenantId: String, builder: EntityConfigurationStatementObjectBuilder) {
        queries.trustAnchorHintQueries.findByAccountId(tenantId)
            .executeAsList()
            .map { it.identifier }
            .forEach { builder.trustAnchorHint(it) }
    }

    private fun addMetadata(tenantId: String, builder: EntityConfigurationStatementObjectBuilder) {
        queries.metadataQueries.findByAccountId(tenantId)
            .executeAsList()
            .forEach {
                builder.metadata(Pair(it.key, Json.parseToJsonElement(it.metadata).jsonObject))
            }
    }

    private fun addMetadataPolicy(tenantId: String, builder: EntityConfigurationStatementObjectBuilder) {
        queries.metadataPolicyQueries.findByAccountId(tenantId)
            .executeAsList()
            .forEach {
                builder.metadataPolicy(Pair(it.key, Json.parseToJsonElement(it.policy).jsonObject))
            }
    }

    private fun addCrits(tenantId: String, builder: EntityConfigurationStatementObjectBuilder) {
        queries.critQueries.findByAccountId(tenantId)
            .executeAsList()
            .map { it.claim }
            .forEach { builder.crit(it) }
    }

    private fun addTrustMarkIssuers(tenantId: String, builder: EntityConfigurationStatementObjectBuilder) {
        queries.trustMarkTypeQueries.findByAccountId(tenantId)
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

    private fun addReceivedTrustMarks(tenantId: String, builder: EntityConfigurationStatementObjectBuilder) {
        queries.receivedTrustMarkQueries.findByAccountId(tenantId)
            .executeAsList()
            .forEach { receivedTrustMark ->
                builder.trustMark(receivedTrustMark.toTrustMark())
            }
    }
}
