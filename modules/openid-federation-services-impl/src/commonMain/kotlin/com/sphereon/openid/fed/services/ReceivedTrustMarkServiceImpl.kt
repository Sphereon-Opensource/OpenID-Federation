package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult

import com.sphereon.openid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.openid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.openid.fed.services.command.receivedTrustMark.CreateReceivedTrustMarkArgs
import com.sphereon.openid.fed.services.command.receivedTrustMark.CreateReceivedTrustMarkCommand
import com.sphereon.openid.fed.services.command.receivedTrustMark.DeleteReceivedTrustMarkArgs
import com.sphereon.openid.fed.services.command.receivedTrustMark.DeleteReceivedTrustMarkCommand
import com.sphereon.openid.fed.services.command.receivedTrustMark.ListReceivedTrustMarksArgs
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

    override suspend fun createReceivedTrustMark(tenantId: String, createRequest: CreateReceivedTrustMark): FederationResult<ReceivedTrustMark> =
        createReceivedTrustMarkCommand.execute(CreateReceivedTrustMarkArgs(tenantId, createRequest)).toFederationResult()

    override suspend fun listReceivedTrustMarks(tenantId: String): FederationResult<Array<ReceivedTrustMark>> =
        listReceivedTrustMarksCommand.execute(ListReceivedTrustMarksArgs(tenantId)).toFederationResult()

    override suspend fun deleteReceivedTrustMark(tenantId: String, trustMarkId: String): FederationResult<ReceivedTrustMark> =
        deleteReceivedTrustMarkCommand.execute(DeleteReceivedTrustMarkArgs(tenantId, trustMarkId)).toFederationResult()
}
