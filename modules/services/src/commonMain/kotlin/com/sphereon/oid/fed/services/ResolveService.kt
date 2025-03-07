package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.client.FederationClient
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.ResolveResponse
import com.sphereon.oid.fed.openapi.models.TrustMark
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ResolveService(
    private val accountService: AccountService,
) {
    private val logger = Logger.tag("ResolveService")
    private val client = FederationClient()

    suspend fun resolveEntity(
        account: Account,
        sub: String,
        trustAnchor: String,
        entityTypes: Array<String>?
    ): ResolveResponse {
        logger.info("Resolving entity for subject: $sub, trust anchor: $trustAnchor")
        try {
            logger.debug("Using account: ${account.username} (ID: ${account.id})")
            logger.debug("Entity types filter: ${entityTypes?.joinToString(", ") ?: "none"}")

            // Get the entity configuration statement for the subject
            logger.debug("Fetching entity configuration statement for subject: $sub")
            val subEntityConfigurationStatement = client.entityConfigurationStatementGet(sub)

            // Get the trust chain from subject to trust anchor
            logger.debug("Resolving trust chain from $sub to trust anchor: $trustAnchor")
            val trustChainResolution = client.trustChainResolve(sub, arrayOf(trustAnchor))
            logger.debug("Trust chain resolution completed: ${trustChainResolution.errorMessage ?: "success"}")

            if (trustChainResolution.errorMessage != null) {
                logger.error("Trust chain resolution failed: ${trustChainResolution.errorMessage}")
                throw IllegalStateException("Failed to resolve trust chain: ${trustChainResolution.errorMessage}")
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

            val response = ResolveResponse(
                iss = accountService.getAccountIdentifierByAccount(account),
                sub = sub,
                iat = currentTime.toString(),
                exp = (currentTime + 3600 * 24).toString(), // 24 hours expiration
                metadata = filteredMetadata,
                trustMarks = trustMarks,
                trustChain = trustChainResolution.trustChain
            )
            logger.debug("Successfully built resolve response")
            return response
        } catch (e: Exception) {
            logger.error("Failed to resolve entity for subject: $sub", e)
            throw e
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
                    val trustAnchorConfig = client.entityConfigurationStatementGet(issuers[0])
                    val validationResult = client.trustMarksVerify(trustMark.trustMark, trustAnchorConfig)

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
