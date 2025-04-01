package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.client.FederationClient
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.ResolveResponse
import com.sphereon.oid.fed.openapi.models.TrustMark
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A service for resolving entities and verifying trust chains, metadata, and trust marks.
 *
 * This class interacts with the FederationClient to fetch entity configuration statements, resolve trust chains,
 * and filter metadata. It also verifies trust marks issued by authorized entities.
 *
 * @property accountService The service responsible for managing account-related operations.
 */
class ResolutionService(
    private val accountService: AccountService,
) {
    /**
     * Logger instance configured with a specific tag for the ResolutionService class.
     * Used to log messages and events related to the operations and functionality
     * of the resolution process in the service.
     */
    private val logger = Logger.tag("ResolutionService")
    /**
     * Instance of the FederationClient used to interact with OpenID Federation
     * services and perform operations such as resolving trust chains, verifying
     * trust marks, and retrieving entity configuration statements.
     *
     * This client is central to performing federation-related functionalities
     * in the context of the ResolutionService.
     */
    private val client = FederationClient()
    /**
     * Represents the number of seconds in one day.
     * This constant is used for time calculations where a daily interval is required.
     */
    private val ONE_DAY_IN_SEC = 3600 * 24

    /**
     * Resolves and retrieves information for a specified entity based on the given parameters, including trust chain resolution,
     * metadata filtering, and trust mark verification. Returns the complete resolution response containing all processed details.
     *
     * @param account The account information of the user initiating the resolution, containing user-specific details.
     * @param sub The entity identifier (subject) whose information is to be resolved.
     * @param trustAnchor The trust anchor against which the entity's trust chain is validated.
     * @param entityTypes Array of entity types used for filtering metadata; can be null to include all types.
     * @return A [ResolveResponse] containing the resolved entity's metadata, verified trust marks, and trust chain details.
     */
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

            val response = buildResolveResponse(currentTime, account, sub, filteredMetadata, trustMarks, trustChainResolution.trustChain)
            logger.debug("Successfully built resolve response")
            return response
        } catch (e: Exception) {
            logger.error("Failed to resolve entity for subject: $sub", e)
            throw e
        }
    }

    /**
     * Builds a `ResolveResponse` object with the provided parameters.
     *
     * @param currentTime The current time in seconds since the Unix epoch.
     * @param account The account associated with the resolution request.
     * @param sub The subject entity identifier for the resolve response.
     * @param metadata Additional contextual information to include in the response.
     * @param trustMarks An array of `TrustMark` objects representing trust marks associated with the entity.
     * @param trustChain An optional array of strings representing the trust chain for validation purposes.
     * @return A `ResolveResponse` object populated with the provided data.
     */
    private fun buildResolveResponse(
        currentTime: Long,
        account: Account,
        sub: String,
        metadata: JsonObject,
        trustMarks: Array<TrustMark>,
        trustChain: Array<String>?
    ): ResolveResponse {
        return ResolveResponse(
            iss = accountService.getAccountIdentifierByAccount(account),
            sub = sub,
            iat = currentTime.toString(),
            exp = (currentTime + ONE_DAY_IN_SEC).toString(),
            metadata = metadata,
            trustMarks = trustMarks,
            trustChain = trustChain
        )
    }

    /**
     * Filters and returns a subset of the metadata from the provided `EntityConfigurationStatement`
     * based on the specified entity types.
     *
     * @param statement The entity configuration statement containing the metadata to filter.
     * @param entityTypes An optional array of entity type strings to filter the metadata keys.
     *                    If null or empty, all metadata is returned.
     * @return A filtered `JsonObject` containing only the metadata entries matching the specified entity types,
     *         or an empty `JsonObject` if no matching entries exist or metadata is absent.
     */
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

    /**
     * Verifies the trust marks provided in the given entity configuration statement and returns an array of verified trust marks.
     *
     * @param subEntityConfigurationStatement The entity configuration statement containing the trust marks and their associated issuers.
     * @return An array of verified trust marks. Returns an empty array if no trust marks are verified or in case of an error.
     */
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
