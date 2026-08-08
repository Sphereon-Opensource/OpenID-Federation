package com.sphereon.openid.fed.client

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.discovery.DiscoverEntitiesArgs
import com.sphereon.openid.fed.client.command.discovery.DiscoverEntitiesCommand
import com.sphereon.openid.fed.client.command.discovery.DiscoverEntitiesResult
import com.sphereon.openid.fed.client.command.discovery.ListSubordinatesCommand
import com.sphereon.openid.fed.client.command.discovery.ListSubordinatesResult
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkCommand
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.TrustChainResolveResponse
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Federation client implementation for reading and validating statements and trust chains.
 *
 * This implementation is provided via DI in session scope. It delegates directly to
 * session-scoped commands for all operations.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FederationClient>())
class FederationClientImpl(
    private val resolveTrustChainCommand: ResolveTrustChainCommand,
    private val verifyTrustChainCommand: VerifyTrustChainCommand,
    private val getEntityConfigurationCommand: GetEntityConfigurationCommand,
    private val verifyTrustMarkCommand: VerifyTrustMarkCommand,
    private val listSubordinatesCommand: ListSubordinatesCommand,
    private val discoverEntitiesCommand: DiscoverEntitiesCommand,
) : FederationClient {

    override suspend fun trustChainResolve(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int
    ): FederationResult<TrustChainResolveResponse> =
        resolveTrustChainCommand.resolveTrustChain(entityIdentifier, trustAnchors, maxDepth)

    override suspend fun trustChainVerify(
        trustChain: Array<String>,
        trustAnchor: String?,
        currentTime: Long?,
        trustAnchorPublicKeys: List<Jwk>?
    ): FederationResult<VerifyTrustChainResponse> =
        verifyTrustChainCommand.verifyTrustChain(trustChain, trustAnchor, currentTime, trustAnchorPublicKeys)

    override suspend fun entityConfigurationStatementGet(entityIdentifier: String): FederationResult<EntityConfigurationStatement> =
        getEntityConfigurationCommand.getEntityConfiguration(entityIdentifier)

    override suspend fun trustMarksVerify(
        trustMark: String,
        trustAnchorConfig: EntityConfigurationStatement,
        currentTime: Long?,
        subject: String?
    ): FederationResult<TrustMarkValidationResponse> =
        verifyTrustMarkCommand.verifyTrustMark(trustMark, trustAnchorConfig, currentTime, subject)

    override suspend fun listSubordinates(
        superiorEntityId: String,
        entityType: String?,
        trustMarked: Boolean?,
        trustMarkType: String?,
        intermediate: Boolean?,
    ): FederationResult<ListSubordinatesResult> =
        listSubordinatesCommand.listSubordinates(
            superiorEntityId, entityType, trustMarked, trustMarkType, intermediate
        )

    override suspend fun discoverEntities(
        startEntityId: String,
        entityType: String?,
        recursive: Boolean,
        maxDepth: Int,
        verifyTrust: Boolean,
        trustAnchors: Array<String>,
        maxDepthTrustChain: Int,
    ): FederationResult<DiscoverEntitiesResult> =
        discoverEntitiesCommand.discoverEntities(
            DiscoverEntitiesArgs(
                startEntityId = startEntityId,
                entityType = entityType,
                recursive = recursive,
                maxDepth = maxDepth,
                verifyTrust = verifyTrust,
                trustAnchors = trustAnchors,
                maxDepthTrustChain = maxDepthTrustChain,
            )
        )
}
