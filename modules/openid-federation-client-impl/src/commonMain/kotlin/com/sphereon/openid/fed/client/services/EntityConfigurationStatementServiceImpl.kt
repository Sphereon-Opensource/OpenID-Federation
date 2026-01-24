package com.sphereon.openid.fed.client.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.command.entityConfiguration.GetFederationEndpointsCommand
import com.sphereon.openid.fed.client.command.entityConfiguration.GetHistoricalKeysCommand
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.FederationEntityMetadata
import com.sphereon.openid.fed.openapi.models.HistoricalKey
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of EntityConfigurationStatementService as a command aggregator.
 *
 * This service aggregates all entity configuration-related commands and provides both
 * direct method access and command-based access for advanced use cases.
 *
 * All methods delegate to individual commands, enabling:
 * - Command composition and chaining
 * - Cross-cutting concerns via command extensions (audit, caching, auth)
 * - Testability through command mocking
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = EntityConfigurationStatementService::class)
class EntityConfigurationStatementServiceImpl(
    private val getEntityConfigurationCommand: GetEntityConfigurationCommand,
    private val getFederationEndpointsCommand: GetFederationEndpointsCommand,
    private val getHistoricalKeysCommand: GetHistoricalKeysCommand
) : EntityConfigurationStatementService {

    /**
     * Inner class implementing the Commands interface.
     * Provides access to individual commands for advanced use cases.
     */
    inner class CommandsImpl : EntityConfigurationStatementService.Commands {
        override val getEntityConfiguration: GetEntityConfigurationCommand
            get() = this@EntityConfigurationStatementServiceImpl.getEntityConfigurationCommand

        override val getFederationEndpoints: GetFederationEndpointsCommand
            get() = this@EntityConfigurationStatementServiceImpl.getFederationEndpointsCommand

        override val getHistoricalKeys: GetHistoricalKeysCommand
            get() = this@EntityConfigurationStatementServiceImpl.getHistoricalKeysCommand
    }

    override val commands: EntityConfigurationStatementService.Commands = CommandsImpl()

    // Delegate all service methods to their respective commands

    override suspend fun getEntityConfiguration(
        entityIdentifier: String
    ): FederationResult<EntityConfigurationStatement> =
        getEntityConfigurationCommand.getEntityConfiguration(entityIdentifier)

    override suspend fun getFederationEndpoints(
        entityConfiguration: EntityConfigurationStatement
    ): FederationResult<FederationEntityMetadata> =
        getFederationEndpointsCommand.getFederationEndpoints(entityConfiguration)

    override suspend fun getHistoricalKeys(
        entityConfiguration: EntityConfigurationStatement
    ): FederationResult<List<HistoricalKey>> =
        getHistoricalKeysCommand.getHistoricalKeys(entityConfiguration)
}
