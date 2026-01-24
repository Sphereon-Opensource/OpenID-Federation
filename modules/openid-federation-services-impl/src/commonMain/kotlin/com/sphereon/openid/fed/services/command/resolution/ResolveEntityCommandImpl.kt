package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.FederationClient
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.NoTrustChainFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.TrustChainValidationFailedError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.ResolveResponse
import com.sphereon.openid.fed.openapi.models.TrustMark
import com.sphereon.openid.fed.services.AccountService
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
    private val accountService: AccountService,
    private val federationClient: FederationClient
) : ExecutionScopedCommandAdapter<ResolveEntityArgs, ResolveResponse, FederationError>(
    id = ResolveEntityCommand.COMMAND_ID,
    execution = execution
), ResolveEntityCommand {

    private val logger = Log.app().withTag("ResolveEntityCommand")
    private val ONE_DAY_IN_SEC = 3600 * 24

    override suspend fun resolveEntity(
        account: Account,
        sub: String,
        trustAnchor: String,
        entityTypes: Array<String>?
    ): IdkResult<ResolveResponse, FederationError> {
        return execute(ResolveEntityArgs(account, sub, trustAnchor, entityTypes), execution.sessionContext)
    }

    override suspend fun doExecute(
        args: ResolveEntityArgs,
        sessionContext: SessionContext,
        applyDuring: (ResolveEntityArgs) -> ResolveEntityArgs
    ): IdkResult<ResolveResponse, FederationError> {
        val (account, sub, trustAnchor, entityTypes) = applyDuring(args)

        logger.info("Resolving entity for subject: $sub, trust anchor: $trustAnchor")

        return try {
            logger.debug("Using account: ${account.username} (ID: ${account.id})")
            logger.debug("Entity types filter: ${entityTypes?.joinToString(", ") ?: "none"}")

            // Get the entity configuration statement for the subject
            logger.debug("Fetching entity configuration statement for subject: $sub")
            val subEntityConfigurationStatement = federationClient.entityConfigurationStatementGet(sub)

            // Get the trust chain from subject to trust anchor
            logger.debug("Resolving trust chain from $sub to trust anchor: $trustAnchor")
            val trustChainResolution = federationClient.trustChainResolve(sub, arrayOf(trustAnchor))
            logger.debug("Trust chain resolution completed: ${trustChainResolution.errorMessage ?: "success"}")

            if (trustChainResolution.errorMessage != null) {
                logger.error("Trust chain resolution failed: ${trustChainResolution.errorMessage}")
                return IdkResult.err(TrustChainValidationFailedError(
                    entityId = sub,
                    reason = trustChainResolution.errorMessage ?: "Unknown error"
                ))
            }

            if (trustChainResolution.trustChain.isNullOrEmpty()) {
                logger.error("No trust chain found for entity: $sub")
                return IdkResult.err(NoTrustChainFoundError(
                    entityId = sub,
                    trustAnchors = listOf(trustAnchor)
                ))
            }

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
                account,
                sub,
                filteredMetadata,
                trustMarks,
                trustChainResolution.trustChain?.toTypedArray()
            )
            logger.debug("Successfully built resolve response")

            response
        } catch (e: Exception) {
            logger.error("Failed to resolve entity for subject: $sub", e)
            IdkResult.err(ServerError(
                reason = "Failed to resolve entity",
                causeDescription = e.message,
                exception = e
            ))
        }
    }

    private suspend fun buildResolveResponse(
        currentTime: Long,
        account: Account,
        sub: String,
        metadata: JsonObject,
        trustMarks: Array<TrustMark>,
        trustChain: Array<String>?
    ): IdkResult<ResolveResponse, FederationError> {
        return accountService.getAccountIdentifierByAccount(account).map { iss ->
            ResolveResponse(
                iss = iss,
                sub = sub,
                iat = currentTime.toInt(),
                exp = (currentTime + ONE_DAY_IN_SEC).toInt(),
                metadata = metadata,
                trustMarks = trustMarks.toList(),
                trustChain = trustChain?.toList()
            )
        }
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
                    val issuers = trustMarkIssuers?.get(trustMark.id)

                    if (issuers.isNullOrEmpty()) {
                        logger.warn("No issuers found for trust mark ${trustMark.id}")
                        continue
                    }

                    // Get the trust anchor configuration and validate the trust mark
                    val trustAnchorConfig = federationClient.entityConfigurationStatementGet(issuers[0])
                    val validationResult = federationClient.trustMarksVerify(trustMark.trustMark, trustAnchorConfig)

                    if (!validationResult.isValid) {
                        verifiedTrustMarks.add(trustMark)
                        logger.debug("Trust mark ${trustMark.id} verified successfully")
                    } else {
                        logger.warn("Trust mark ${trustMark.id} verification failed: ${validationResult.errorMessage}")
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to verify trust mark ${trustMark.id}: ${e.message}")
                }
            }

            return if (verifiedTrustMarks.isEmpty()) arrayOf() else verifiedTrustMarks.toTypedArray()
        } catch (e: Exception) {
            logger.error("Error verifying trust marks: ${e.message}")
            return arrayOf()
        }
    }
}
