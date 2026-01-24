package com.sphereon.openid.fed.client.services

import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommandService
import com.sphereon.openid.fed.client.command.entityConfiguration.GetFederationEndpointsCommand
import com.sphereon.openid.fed.client.command.entityConfiguration.GetFederationEndpointsCommandService
import com.sphereon.openid.fed.client.command.entityConfiguration.GetHistoricalKeysCommand
import com.sphereon.openid.fed.client.command.entityConfiguration.GetHistoricalKeysCommandService
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.FederationEntityMetadata
import com.sphereon.openid.fed.openapi.models.HistoricalKey

/**
 * Service interface for entity configuration statement operations (client-side).
 *
 * Provides functionality to fetch, parse, and extract information from
 * entity configuration statements according to the OpenID Federation specification.
 *
 * This service aggregates all entity configuration-related commands and provides both
 * direct method access and command-based access patterns.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface EntityConfigurationStatementService :
    GetEntityConfigurationCommandService,
    GetFederationEndpointsCommandService,
    GetHistoricalKeysCommandService {

    /**
     * Provides access to individual entity configuration commands for advanced use cases
     * like composition, chaining, or extension-based processing.
     */
    val commands: Commands

    /**
     * Container interface for all entity configuration-related commands.
     */
    interface Commands {
        val getEntityConfiguration: GetEntityConfigurationCommand
        val getFederationEndpoints: GetFederationEndpointsCommand
        val getHistoricalKeys: GetHistoricalKeysCommand
    }

    /**
     * Get an Entity Configuration Statement from an entity.
     *
     * @param entityIdentifier The entity identifier for which to get the statement.
     * @return FederationResult containing the EntityConfigurationStatement or an error.
     */
    override suspend fun getEntityConfiguration(
        entityIdentifier: String
    ): FederationResult<EntityConfigurationStatement>

    /**
     * Gets federation endpoints from an EntityConfigurationStatement.
     *
     * @param entityConfiguration The entity configuration statement.
     * @return FederationResult containing the FederationEntityMetadata or an error.
     */
    override suspend fun getFederationEndpoints(
        entityConfiguration: EntityConfigurationStatement
    ): FederationResult<FederationEntityMetadata>

    /**
     * Retrieves the historical keys from the federation entity's historical keys endpoint.
     *
     * @param entityConfiguration The entity configuration statement.
     * @return FederationResult containing the list of HistoricalKey or an error.
     */
    override suspend fun getHistoricalKeys(
        entityConfiguration: EntityConfigurationStatement
    ): FederationResult<List<HistoricalKey>>
}
