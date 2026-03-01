package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult

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

    override suspend fun createMetadata(tenantId: String, key: String, metadata: JsonElement): FederationResult<Metadata> =
        createMetadataCommand.execute(CreateMetadataArgs(tenantId, key, metadata)).toFederationResult()

    override suspend fun findByAccount(tenantId: String): FederationResult<List<Metadata>> =
        findMetadataByAccountCommand.execute(FindMetadataByAccountArgs(tenantId)).toFederationResult()

    override suspend fun deleteMetadata(tenantId: String, id: String): FederationResult<Metadata> =
        deleteMetadataCommand.execute(DeleteMetadataArgs(tenantId, id)).toFederationResult()
}
