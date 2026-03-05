package com.sphereon.openid.fed.client

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkCommand
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.TrustChainResolveResponse
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Federation client implementation for reading and validating statements and trust chains.
 *
 * This implementation is provided via DI in session scope. It delegates directly to
 * session-scoped commands for all operations.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FederationClient::class)
class FederationClientImpl(
    private val resolveTrustChainCommand: ResolveTrustChainCommand,
    private val verifyTrustChainCommand: VerifyTrustChainCommand,
    private val getEntityConfigurationCommand: GetEntityConfigurationCommand,
    private val verifyTrustMarkCommand: VerifyTrustMarkCommand
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
        currentTime: Long?
    ): FederationResult<VerifyTrustChainResponse> =
        verifyTrustChainCommand.verifyTrustChain(trustChain, trustAnchor, currentTime)

    override suspend fun entityConfigurationStatementGet(entityIdentifier: String): FederationResult<EntityConfigurationStatement> =
        getEntityConfigurationCommand.getEntityConfiguration(entityIdentifier)

    override suspend fun trustMarksVerify(
        trustMark: String,
        trustAnchorConfig: EntityConfigurationStatement,
        currentTime: Long?
    ): FederationResult<TrustMarkValidationResponse> =
        verifyTrustMarkCommand.verifyTrustMark(trustMark, trustAnchorConfig, currentTime)
}
