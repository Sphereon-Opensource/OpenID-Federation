package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult

import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.services.command.entityConfiguration.FindEntityConfigurationByAccountArgs
import com.sphereon.openid.fed.services.command.entityConfiguration.FindEntityConfigurationByAccountCommand
import com.sphereon.openid.fed.services.command.entityConfiguration.PublishEntityConfigurationArgs
import com.sphereon.openid.fed.services.command.entityConfiguration.PublishEntityConfigurationCommand
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

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
@ContributesBinding(SessionScope::class, binding = binding<EntityConfigurationStatementService>())
class EntityConfigurationStatementServiceImpl(
    private val findEntityConfigurationByAccountCommand: FindEntityConfigurationByAccountCommand,
    private val publishEntityConfigurationCommand: PublishEntityConfigurationCommand
) : EntityConfigurationStatementService {

    override suspend fun findByAccount(tenantId: String): FederationResult<EntityConfigurationStatement> =
        findEntityConfigurationByAccountCommand.execute(FindEntityConfigurationByAccountArgs(tenantId)).toFederationResult()

    override suspend fun publishByAccount(
        tenantId: String,
        dryRun: Boolean?,
        kmsKeyRef: String?,
        kid: String?
    ): FederationResult<String> =
        publishEntityConfigurationCommand.execute(PublishEntityConfigurationArgs(tenantId, dryRun, kmsKeyRef, kid)).toFederationResult()
}
