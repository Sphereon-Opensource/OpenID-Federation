package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.FederationClient
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.TenantNotFoundError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.ResolveResponse
import com.sphereon.openid.fed.openapi.models.TrustMark
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the ResolveEntityCommand.
 * Resolves an entity and retrieves its information including trust chain, metadata, and trust marks.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ResolveEntityCommand::class)
class ResolveEntityCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val federationClient: FederationClient
) : TypedServiceCommandAdapter<ResolveEntityArgs, ResolveResponse>(
    commandId = ResolveEntityCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<ResolveEntityArgs>(),
    outputTypeToken = typeToken<ResolveResponse>()
), ResolveEntityCommand {

    private val logger = execution.federationLogger("ResolveEntityCommand")
    private val ONE_DAY_IN_SEC = 3600 * 24

    override suspend fun doExecute(
        args: ResolveEntityArgs,
        applyDuring: (ResolveEntityArgs) -> ResolveEntityArgs
    ): IdkResult<ResolveResponse, IdkError> {
        val (tenantId, sub, trustAnchor, entityTypes) = applyDuring(args)

        logger.info("Resolving entity for subject: $sub, trust anchor: $trustAnchor")

        return try {
            logger.debug("Using tenant: $tenantId")
            logger.debug("Entity types filter: ${entityTypes?.joinToString(", ") ?: "none"}")

            // Get the entity configuration statement for the subject
            logger.debug("Fetching entity configuration statement for subject: $sub")
            val entityConfigResult = federationClient.entityConfigurationStatementGet(sub)
            if (entityConfigResult.isErr) {
                logger.error("Failed to fetch entity configuration for subject: $sub")
                return federationErr(entityConfigResult.error)
            }
            val subEntityConfigurationStatement = entityConfigResult.value

            // Get the trust chain from subject to trust anchor
            logger.debug("Resolving trust chain from $sub to trust anchor: $trustAnchor")
            val trustChainResult = federationClient.trustChainResolve(sub, arrayOf(trustAnchor))
            if (trustChainResult.isErr) {
                logger.error("Trust chain resolution failed for entity: $sub")
                return federationErr(trustChainResult.error)
            }
            val trustChainResolution = trustChainResult.value
            logger.debug("Trust chain resolution completed successfully")

            // Get metadata based on entity types
            logger.debug("Filtering metadata based on entity types")
            val filteredMetadata = getFilteredMetadata(subEntityConfigurationStatement, entityTypes)
            logger.debug("Metadata filtering completed")

            // Get and verify trust marks
            logger.debug("Getting and verifying trust marks for subject: $sub")
            val trustMarks = getVerifiedTrustMarks(subEntityConfigurationStatement)
            logger.debug("Trust marks verification completed")

            val currentTime = System.currentTimeMillis() / 1000
            logger.debug("Building resolve response with current time: $currentTime")

            val response = buildResolveResponse(
                currentTime,
                tenantId,
                sub,
                filteredMetadata,
                trustMarks,
                trustChainResolution.trustChain.toTypedArray()
            )
            logger.debug("Successfully built resolve response")

            response
        } catch (e: Exception) {
            logger.error("Failed to resolve entity for subject: $sub", e)
            federationErr(ServerError(
                reason = "Failed to resolve entity",
                causeDescription = e.message,
                exception = e
            ))
        }
    }

    private suspend fun buildResolveResponse(
        currentTime: Long,
        tenantId: String,
        sub: String,
        metadata: JsonObject,
        trustMarks: Array<TrustMark>,
        trustChain: Array<String>
    ): IdkResult<ResolveResponse, IdkError> {
        val iss = tenantContextResolver.resolveIdentifier(tenantId)
            ?: return federationErr(TenantNotFoundError(tenantId))

        return IdkResult.ok(
            ResolveResponse(
                iss = iss,
                sub = sub,
                iat = currentTime.toDouble(),
                exp = (currentTime + ONE_DAY_IN_SEC).toDouble(),
                metadata = metadata,
                trustMarks = trustMarks.toList(),
                trustChain = trustChain.toList()
            )
        )
    }

    private fun getFilteredMetadata(
        statement: EntityConfigurationStatement,
        entityTypes: Array<String>?
    ): JsonObject {
        try {
            val metadata = statement.metadata ?: return JsonObject(mapOf())

            if (entityTypes.isNullOrEmpty()) {
                return metadata.jsonObject
            }

            val filteredEntries = metadata.jsonObject.entries.filter { (key, _) ->
                entityTypes.contains(key)
            }

            return JsonObject(filteredEntries.associate { it.key to it.value })
        } catch (e: Exception) {
            logger.error("Failed to filter metadata", e)
            throw e
        }
    }

    private suspend fun getVerifiedTrustMarks(subEntityConfigurationStatement: EntityConfigurationStatement): Array<TrustMark> {
        try {
            val trustMarks = subEntityConfigurationStatement.trustMarks ?: return arrayOf()
            val verifiedTrustMarks = mutableListOf<TrustMark>()

            for (trustMark in trustMarks) {
                try {
                    // Get the trust anchor config from the trust mark issuers mapping
                    val trustMarkIssuers = subEntityConfigurationStatement.trustMarkIssuers
                    val issuers = trustMarkIssuers?.get(trustMark.trustMarkType)

                    if (issuers.isNullOrEmpty()) {
                        logger.warn("No issuers found for trust mark ${trustMark.trustMarkType}")
                        continue
                    }

                    // Get the trust anchor configuration and validate the trust mark
                    val configResult = federationClient.entityConfigurationStatementGet(issuers[0])
                    if (configResult.isErr) {
                        logger.warn("Failed to fetch issuer config for trust mark ${trustMark.trustMarkType}: ${configResult.error.message.defaultMessage}")
                        continue
                    }

                    val validationResult = federationClient.trustMarksVerify(trustMark.trustMark, configResult.value)

                    if (validationResult.isOk) {
                        verifiedTrustMarks.add(trustMark)
                        logger.debug("Trust mark ${trustMark.trustMarkType} verified successfully")
                    } else {
                        logger.warn("Trust mark ${trustMark.trustMarkType} verification failed: ${validationResult.error.message.defaultMessage}")
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to verify trust mark ${trustMark.trustMarkType}: ${e.message}")
                }
            }

            return if (verifiedTrustMarks.isEmpty()) arrayOf() else verifiedTrustMarks.toTypedArray()
        } catch (e: Exception) {
            logger.error("Error verifying trust marks: ${e.message}")
            return arrayOf()
        }
    }
}
