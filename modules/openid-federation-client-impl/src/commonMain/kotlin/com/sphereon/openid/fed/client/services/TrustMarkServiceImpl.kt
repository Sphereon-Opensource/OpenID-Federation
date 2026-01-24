package com.sphereon.openid.fed.client.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkCommand
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of TrustMarkService as a command aggregator.
 *
 * This service aggregates all trust mark-related commands and provides both
 * direct method access and command-based access for advanced use cases.
 *
 * All methods delegate to individual commands, enabling:
 * - Command composition and chaining
 * - Cross-cutting concerns via command extensions (audit, caching, auth)
 * - Testability through command mocking
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = TrustMarkService::class)
class TrustMarkServiceImpl(
    private val verifyTrustMarkCommand: VerifyTrustMarkCommand
) : TrustMarkService {

    /**
     * Inner class implementing the Commands interface.
     * Provides access to individual commands for advanced use cases.
     */
    inner class CommandsImpl : TrustMarkService.Commands {
        override val verifyTrustMark: VerifyTrustMarkCommand
            get() = this@TrustMarkServiceImpl.verifyTrustMarkCommand
    }

    override val commands: TrustMarkService.Commands = CommandsImpl()

    // Delegate all service methods to their respective commands

    override suspend fun verifyTrustMark(
        trustMark: String,
        trustAnchorConfig: EntityConfigurationStatement,
        currentTime: Long?
    ): FederationResult<TrustMarkValidationResponse> =
        verifyTrustMarkCommand.verifyTrustMark(trustMark, trustAnchorConfig, currentTime)
}
