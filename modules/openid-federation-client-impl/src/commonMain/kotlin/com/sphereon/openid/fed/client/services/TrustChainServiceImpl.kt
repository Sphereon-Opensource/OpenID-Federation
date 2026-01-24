package com.sphereon.openid.fed.client.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.TrustChainResolveResponse
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of TrustChainService as a command aggregator.
 *
 * This service aggregates all trust chain-related commands and provides both
 * direct method access and command-based access for advanced use cases.
 *
 * All methods delegate to individual commands, enabling:
 * - Command composition and chaining
 * - Cross-cutting concerns via command extensions (audit, caching, auth)
 * - Testability through command mocking
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = TrustChainService::class)
class TrustChainServiceImpl(
    private val resolveTrustChainCommand: ResolveTrustChainCommand,
    private val verifyTrustChainCommand: VerifyTrustChainCommand
) : TrustChainService {

    /**
     * Inner class implementing the Commands interface.
     * Provides access to individual commands for advanced use cases.
     */
    inner class CommandsImpl : TrustChainService.Commands {
        override val resolveTrustChain: ResolveTrustChainCommand
            get() = this@TrustChainServiceImpl.resolveTrustChainCommand

        override val verifyTrustChain: VerifyTrustChainCommand
            get() = this@TrustChainServiceImpl.verifyTrustChainCommand
    }

    override val commands: TrustChainService.Commands = CommandsImpl()

    // Delegate all service methods to their respective commands

    override suspend fun resolveTrustChain(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int
    ): FederationResult<TrustChainResolveResponse> =
        resolveTrustChainCommand.resolveTrustChain(entityIdentifier, trustAnchors, maxDepth)

    override suspend fun verifyTrustChain(
        trustChain: Array<String>,
        trustAnchor: String?,
        currentTime: Long?
    ): FederationResult<VerifyTrustChainResponse> =
        verifyTrustChainCommand.verifyTrustChain(trustChain, trustAnchor, currentTime)
}
