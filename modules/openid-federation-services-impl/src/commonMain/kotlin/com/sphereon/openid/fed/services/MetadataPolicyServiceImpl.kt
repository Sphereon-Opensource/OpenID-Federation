package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult

import com.sphereon.openid.fed.openapi.models.MetadataPolicy
import com.sphereon.openid.fed.services.command.metadataPolicy.CreateMetadataPolicyArgs
import com.sphereon.openid.fed.services.command.metadataPolicy.CreateMetadataPolicyCommand
import com.sphereon.openid.fed.services.command.metadataPolicy.DeleteMetadataPolicyArgs
import com.sphereon.openid.fed.services.command.metadataPolicy.DeleteMetadataPolicyCommand
import com.sphereon.openid.fed.services.command.metadataPolicy.FindMetadataPolicyByAccountArgs
import com.sphereon.openid.fed.services.command.metadataPolicy.FindMetadataPolicyByAccountCommand
import kotlinx.serialization.json.JsonElement
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

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
@ContributesBinding(SessionScope::class, binding = binding<MetadataPolicyService>())
class MetadataPolicyServiceImpl(
    private val createMetadataPolicyCommand: CreateMetadataPolicyCommand,
    private val deleteMetadataPolicyCommand: DeleteMetadataPolicyCommand,
    private val findMetadataPolicyByAccountCommand: FindMetadataPolicyByAccountCommand
) : MetadataPolicyService {

    override suspend fun createPolicy(tenantId: String, key: String, policy: JsonElement): FederationResult<MetadataPolicy> =
        createMetadataPolicyCommand.execute(CreateMetadataPolicyArgs(tenantId, key, policy)).toFederationResult()

    override suspend fun findByAccount(tenantId: String): FederationResult<List<MetadataPolicy>> =
        findMetadataPolicyByAccountCommand.execute(FindMetadataPolicyByAccountArgs(tenantId)).toFederationResult()

    override suspend fun deletePolicy(tenantId: String, id: String): FederationResult<MetadataPolicy> =
        deleteMetadataPolicyCommand.execute(DeleteMetadataPolicyArgs(tenantId, id)).toFederationResult()
}
