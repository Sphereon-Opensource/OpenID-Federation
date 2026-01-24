package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.services.command.entityConfiguration.FindEntityConfigurationByAccountCommand
import com.sphereon.openid.fed.services.command.entityConfiguration.PublishEntityConfigurationCommand
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
    private val findEntityConfigurationByAccountCommand: FindEntityConfigurationByAccountCommand,
    private val publishEntityConfigurationCommand: PublishEntityConfigurationCommand
) : EntityConfigurationStatementService {

    /**
     * Inner class implementing the Commands interface.
     * Provides access to individual commands for advanced use cases.
     */
    inner class CommandsImpl : EntityConfigurationStatementService.Commands {
        override val findByAccount: FindEntityConfigurationByAccountCommand
            get() = this@EntityConfigurationStatementServiceImpl.findEntityConfigurationByAccountCommand

        override val publishByAccount: PublishEntityConfigurationCommand
            get() = this@EntityConfigurationStatementServiceImpl.publishEntityConfigurationCommand
    }

    override val commands: EntityConfigurationStatementService.Commands = CommandsImpl()

    // Delegate all service methods to their respective commands

    override suspend fun findByAccount(account: Account): FederationResult<EntityConfigurationStatement> =
        findEntityConfigurationByAccountCommand.findByAccount(account)

    override suspend fun publishByAccount(
        account: Account,
        dryRun: Boolean?,
        kmsKeyRef: String?,
        kid: String?
    ): FederationResult<String> =
        publishEntityConfigurationCommand.publishByAccount(account, dryRun, kmsKeyRef, kid)
}
