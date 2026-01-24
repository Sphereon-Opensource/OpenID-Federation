package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.MetadataPolicy
import com.sphereon.openid.fed.services.command.metadataPolicy.CreateMetadataPolicyCommand
import com.sphereon.openid.fed.services.command.metadataPolicy.DeleteMetadataPolicyCommand
import com.sphereon.openid.fed.services.command.metadataPolicy.FindMetadataPolicyByAccountCommand
import kotlinx.serialization.json.JsonElement
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of MetadataPolicyService as a command aggregator.
 *
 * This service aggregates all metadata policy-related commands and provides both
 * direct method access and command-based access for advanced use cases.
 *
 * All methods delegate to individual commands, enabling:
 * - Command composition and chaining
 * - Cross-cutting concerns via command extensions (audit, caching, auth)
 * - Testability through command mocking
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = MetadataPolicyService::class)
class MetadataPolicyServiceImpl(
    private val createMetadataPolicyCommand: CreateMetadataPolicyCommand,
    private val deleteMetadataPolicyCommand: DeleteMetadataPolicyCommand,
    private val findMetadataPolicyByAccountCommand: FindMetadataPolicyByAccountCommand
) : MetadataPolicyService {

    /**
     * Inner class implementing the Commands interface.
     * Provides access to individual commands for advanced use cases.
     */
    inner class CommandsImpl : MetadataPolicyService.Commands {
        override val createPolicy: CreateMetadataPolicyCommand
            get() = this@MetadataPolicyServiceImpl.createMetadataPolicyCommand

        override val deletePolicy: DeleteMetadataPolicyCommand
            get() = this@MetadataPolicyServiceImpl.deleteMetadataPolicyCommand

        override val findByAccount: FindMetadataPolicyByAccountCommand
            get() = this@MetadataPolicyServiceImpl.findMetadataPolicyByAccountCommand
    }

    override val commands: MetadataPolicyService.Commands = CommandsImpl()

    // Delegate all service methods to their respective commands

    override suspend fun createPolicy(account: Account, key: String, policy: JsonElement): FederationResult<MetadataPolicy> =
        createMetadataPolicyCommand.createPolicy(account, key, policy)

    override suspend fun findByAccount(account: Account): FederationResult<List<MetadataPolicy>> =
        findMetadataPolicyByAccountCommand.findByAccount(account)

    override suspend fun deletePolicy(account: Account, id: String): FederationResult<MetadataPolicy> =
        deleteMetadataPolicyCommand.deletePolicy(account, id)
}
