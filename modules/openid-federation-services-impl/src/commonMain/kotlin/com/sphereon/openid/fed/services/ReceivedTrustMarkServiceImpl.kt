package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.openid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.openid.fed.services.command.receivedTrustMark.CreateReceivedTrustMarkCommand
import com.sphereon.openid.fed.services.command.receivedTrustMark.DeleteReceivedTrustMarkCommand
import com.sphereon.openid.fed.services.command.receivedTrustMark.ListReceivedTrustMarksCommand
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of ReceivedTrustMarkService as a command aggregator.
 *
 * This service aggregates all received trust mark-related commands and provides both
 * direct method access and command-based access for advanced use cases.
 *
 * All methods delegate to individual commands, enabling:
 * - Command composition and chaining
 * - Cross-cutting concerns via command extensions (audit, caching, auth)
 * - Testability through command mocking
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ReceivedTrustMarkService::class)
class ReceivedTrustMarkServiceImpl(
    private val createReceivedTrustMarkCommand: CreateReceivedTrustMarkCommand,
    private val deleteReceivedTrustMarkCommand: DeleteReceivedTrustMarkCommand,
    private val listReceivedTrustMarksCommand: ListReceivedTrustMarksCommand
) : ReceivedTrustMarkService {

    /**
     * Inner class implementing the Commands interface.
     * Provides access to individual commands for advanced use cases.
     */
    inner class CommandsImpl : ReceivedTrustMarkService.Commands {
        override val createReceivedTrustMark: CreateReceivedTrustMarkCommand
            get() = this@ReceivedTrustMarkServiceImpl.createReceivedTrustMarkCommand

        override val deleteReceivedTrustMark: DeleteReceivedTrustMarkCommand
            get() = this@ReceivedTrustMarkServiceImpl.deleteReceivedTrustMarkCommand

        override val listReceivedTrustMarks: ListReceivedTrustMarksCommand
            get() = this@ReceivedTrustMarkServiceImpl.listReceivedTrustMarksCommand
    }

    override val commands: ReceivedTrustMarkService.Commands = CommandsImpl()

    // Delegate all service methods to their respective commands

    override suspend fun createReceivedTrustMark(account: Account, createRequest: CreateReceivedTrustMark): FederationResult<ReceivedTrustMark> =
        createReceivedTrustMarkCommand.createReceivedTrustMark(account, createRequest)

    override suspend fun listReceivedTrustMarks(account: Account): FederationResult<Array<ReceivedTrustMark>> =
        listReceivedTrustMarksCommand.listReceivedTrustMarks(account)

    override suspend fun deleteReceivedTrustMark(account: Account, trustMarkId: String): FederationResult<ReceivedTrustMark> =
        deleteReceivedTrustMarkCommand.deleteReceivedTrustMark(account, trustMarkId)
}
