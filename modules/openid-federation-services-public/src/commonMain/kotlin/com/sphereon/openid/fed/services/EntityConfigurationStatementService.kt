package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement

/**
 * Service interface responsible for managing entity configuration statements.
 * It provides functionality to generate, publish, and persist
 * entity configuration statements for a given account.
 *
 * This service aggregates all entity configuration-related commands and provides
 * direct method access.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface EntityConfigurationStatementService {

    /**
     * Retrieves the Entity Configuration Statement for a given account.
     *
     * @param account The account for which the entity configuration statement is to be retrieved.
     * @return FederationResult containing the EntityConfigurationStatement or an error.
     */
    suspend fun findByAccount(account: Account): FederationResult<EntityConfigurationStatement>

    /**
     * Publishes the entity configuration statement for the specified account.
     * Optionally supports a dry run mode where the resulting JWT is generated but not persisted.
     *
     * @param account The account for which the entity configuration statement is being published.
     * @param dryRun If true, the operation will simulate publishing without persisting the result.
     * @param kmsKeyRef Optional KMS key reference.
     * @param kid Optional key ID.
     * @return FederationResult containing the JWT or an error.
     */
    suspend fun publishByAccount(
        account: Account,
        dryRun: Boolean?,
        kmsKeyRef: String?,
        kid: String?
    ): FederationResult<String>
}
