package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.Metadata
import com.sphereon.openid.fed.services.command.metadata.CreateMetadataArgs
import com.sphereon.openid.fed.services.command.metadata.CreateMetadataCommand
import com.sphereon.openid.fed.services.command.metadata.DeleteMetadataArgs
import com.sphereon.openid.fed.services.command.metadata.DeleteMetadataCommand
import com.sphereon.openid.fed.services.command.metadata.FindMetadataByAccountArgs
import com.sphereon.openid.fed.services.command.metadata.FindMetadataByAccountCommand
import kotlinx.serialization.json.JsonElement
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of MetadataService as a command aggregator.
 *
 * This service aggregates all metadata-related commands and provides both
 * direct method access and command-based access for advanced use cases.
 *
 * All methods delegate to individual commands, enabling:
 * - Command composition and chaining
 * - Cross-cutting concerns via command extensions (audit, caching, auth)
 * - Testability through command mocking
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = MetadataService::class)
class MetadataServiceImpl(
    private val createMetadataCommand: CreateMetadataCommand,
    private val deleteMetadataCommand: DeleteMetadataCommand,
    private val findMetadataByAccountCommand: FindMetadataByAccountCommand
) : MetadataService {

    override suspend fun createMetadata(account: Account, key: String, metadata: JsonElement): FederationResult<Metadata> =
        createMetadataCommand.execute(CreateMetadataArgs(account, key, metadata)).toFederationResult()

    override suspend fun findByAccount(account: Account): FederationResult<List<Metadata>> =
        findMetadataByAccountCommand.execute(FindMetadataByAccountArgs(account)).toFederationResult()

    override suspend fun deleteMetadata(account: Account, id: String): FederationResult<Metadata> =
        deleteMetadataCommand.execute(DeleteMetadataArgs(account, id)).toFederationResult()
}
