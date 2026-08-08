package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.builder.EntityConfigurationStatementObjectBuilder
import com.sphereon.openid.fed.common.builder.FederationEntityMetadataObjectBuilder
import com.sphereon.openid.fed.core.config.FederationEndpointKind
import com.sphereon.openid.fed.core.config.OidfConfigBinder
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the FindEntityConfigurationByAccountCommand.
 * Retrieves the entity configuration statement for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FindEntityConfigurationByAccountCommand>())
class FindEntityConfigurationByAccountCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val jwkService: JwkService,
    private val configBinder: OidfConfigBinder,
) : TypedServiceCommandAdapter<FindEntityConfigurationByAccountArgs, EntityConfigurationStatement, FederationError>(
    commandId = FindEntityConfigurationByAccountCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<FindEntityConfigurationByAccountArgs>(),
    outputTypeToken = typeToken<EntityConfigurationStatement>()
), FindEntityConfigurationByAccountCommand {

    private val logger = execution.federationLogger("FindEntityConfigurationByAccountCommand")
    private val queries = Persistence

    companion object {
        private const val EXPIRATION_PERIOD_SECONDS = 3600L * 24 * 365
    }

    override suspend fun doExecute(
        args: FindEntityConfigurationByAccountArgs,
        applyDuring: (FindEntityConfigurationByAccountArgs) -> FindEntityConfigurationByAccountArgs
    ): IdkResult<EntityConfigurationStatement, FederationError> {
        val (tenantId) = applyDuring(args)

        logger.info("Finding entity configuration for account: $tenantId")

        return getEntityConfigurationStatement(tenantId)
    }

    private suspend fun getEntityConfigurationStatement(tenantId: String): IdkResult<EntityConfigurationStatement, FederationError> {
        logger.info("Building entity configuration for account: $tenantId")

        val identifier = tenantContextResolver.resolveIdentifier(tenantId)
            ?: return federationErr(TenantNotFoundError(tenantId))

        val keysResult = jwkService.getKeys(tenantId, includeRevoked = false)
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
            .iat(currentTimeSeconds.toDouble())
            .exp((currentTimeSeconds + EXPIRATION_PERIOD_SECONDS).toDouble())
            .jwks(keys.toMutableList())
    }

    private fun addComponents(
        tenantId: String,
        builder: EntityConfigurationStatementObjectBuilder,
        identifier: String
    ) {
        addFederationEntityMetadata(tenantId, builder, identifier)
        addMetadata(tenantId, builder)
        // metadata_policy belongs only on Subordinate Statements (OIDFed 1.1 §3.1.3)
        addAuthorityHints(tenantId, builder)
        addTrustAnchorHints(tenantId, builder)
        addCrits(tenantId, builder)
        addTrustMarkIssuers(tenantId, builder)
        addReceivedTrustMarks(tenantId, builder)
    }

    /**
     * Auto-inject `federation_entity` metadata per OIDFed 1.1 §5.1.1:
     * - Trust Anchors / Intermediates MUST publish fetch + list (authority endpoints).
     * - Leaves MUST NOT publish fetch/list.
     * - Trust Mark Issuers SHOULD publish status endpoints (even as leaves).
     *
     * Classification:
     * - Authority: has subordinates, OR has no authority_hints (federation root / TA bootstrap)
     * - Leaf: has authority_hints and no subordinates
     */
    private fun addFederationEntityMetadata(
        tenantId: String,
        builder: EntityConfigurationStatementObjectBuilder,
        identifier: String
    ) {
        val hasSubordinates = queries.subordinateQueries.findByAccountId(tenantId).executeAsList().isNotEmpty()
        val hasAuthorityHints = queries.authorityHintQueries.findByAccountId(tenantId).executeAsList().isNotEmpty()
        val issuedTrustMarks = queries.trustMarkQueries.findByAccountId(tenantId).executeAsList().isNotEmpty()

        // Authority = Intermediate (has subordinates) or Trust Anchor bootstrap (no authority_hints)
        val isAuthority = hasSubordinates || !hasAuthorityHints

        if (!isAuthority && !issuedTrustMarks) {
            // Pure leaf with no TM issuance: do not auto-inject federation_entity
            return
        }

        val fedConfig = configBinder.getFederationConfig()
        val authMethods = fedConfig.endpointAuthMethods
        val federationEntityMetadata = FederationEntityMetadataObjectBuilder()
            .identifier(identifier)
            .authorityEndpoints(enabled = isAuthority) // leaves never get fetch/list
            .trustMarkEndpoints(enabled = issuedTrustMarks || isAuthority)
            .resolveEndpoint(enabled = true)
            .historicalKeysEndpoint(enabled = true)
            .endpointAuthSigningAlgValuesSupported(
                // Only advertise algs when private_key_jwt is enabled somewhere (OIDFed §8.8.1)
                if (authMethods.anyPrivateKeyJwt()) fedConfig.endpointAuthSigningAlgs else null
            )
            .build()

        val base = Json.encodeToJsonElement(
            FederationEntityMetadata.serializer(),
            federationEntityMetadata
        ).jsonObject.toMutableMap()

        // OIDFed §8.8.1: advertise non-default *_auth_methods (default ["none"] is omit-able)
        fun putAuthMethods(kind: FederationEndpointKind, methods: List<String>) {
            val normalized = methods.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (normalized.isEmpty() || normalized == listOf("none")) return
            // Only advertise for endpoints we actually publish
            val endpointUrlPresent = when (kind) {
                FederationEndpointKind.FETCH -> federationEntityMetadata.federationFetchEndpoint != null
                FederationEndpointKind.LIST -> federationEntityMetadata.federationListEndpoint != null
                FederationEndpointKind.RESOLVE -> federationEntityMetadata.federationResolveEndpoint != null
                FederationEndpointKind.TRUST_MARK_STATUS -> federationEntityMetadata.federationTrustMarkStatusEndpoint != null
                FederationEndpointKind.TRUST_MARK_LIST -> federationEntityMetadata.federationTrustMarkListEndpoint != null
                FederationEndpointKind.TRUST_MARK -> federationEntityMetadata.federationTrustMarkEndpoint != null
                FederationEndpointKind.HISTORICAL_KEYS -> federationEntityMetadata.federationHistoricalKeysEndpoint != null
            }
            if (!endpointUrlPresent) return
            base[kind.authMethodsMetadataName] = JsonArray(normalized.map { JsonPrimitive(it) })
        }
        putAuthMethods(FederationEndpointKind.FETCH, authMethods.fetch)
        putAuthMethods(FederationEndpointKind.LIST, authMethods.list)
        putAuthMethods(FederationEndpointKind.RESOLVE, authMethods.resolve)
        putAuthMethods(FederationEndpointKind.TRUST_MARK_STATUS, authMethods.trustMarkStatus)
        putAuthMethods(FederationEndpointKind.TRUST_MARK_LIST, authMethods.trustMarkList)
        putAuthMethods(FederationEndpointKind.TRUST_MARK, authMethods.trustMark)
        putAuthMethods(FederationEndpointKind.HISTORICAL_KEYS, authMethods.historicalKeys)

        builder.metadata(Pair("federation_entity", JsonObject(base)))
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
