package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.models.Crit as CritEntity
import com.sphereon.openid.fed.services.command.criticalClaim.CreateCriticalClaimCommand
import com.sphereon.openid.fed.services.command.criticalClaim.DeleteCriticalClaimCommand
import com.sphereon.openid.fed.services.command.criticalClaim.FindCriticalClaimsByAccountCommand
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of CriticalClaimService as a command aggregator.
 *
 * This service aggregates all critical claim-related commands and provides both
 * direct method access and command-based access for advanced use cases.
 *
 * All methods delegate to individual commands, enabling:
 * - Command composition and chaining
 * - Cross-cutting concerns via command extensions (audit, caching, auth)
 * - Testability through command mocking
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CriticalClaimService::class)
class CriticalClaimServiceImpl(
    private val createCriticalClaimCommand: CreateCriticalClaimCommand,
    private val deleteCriticalClaimCommand: DeleteCriticalClaimCommand,
    private val findCriticalClaimsByAccountCommand: FindCriticalClaimsByAccountCommand
) : CriticalClaimService {

    /**
     * Inner class implementing the Commands interface.
     * Provides access to individual commands for advanced use cases.
     */
    inner class CommandsImpl : CriticalClaimService.Commands {
        override val create: CreateCriticalClaimCommand
            get() = this@CriticalClaimServiceImpl.createCriticalClaimCommand

        override val delete: DeleteCriticalClaimCommand
            get() = this@CriticalClaimServiceImpl.deleteCriticalClaimCommand

        override val findByAccount: FindCriticalClaimsByAccountCommand
            get() = this@CriticalClaimServiceImpl.findCriticalClaimsByAccountCommand
    }

    override val commands: CriticalClaimService.Commands = CommandsImpl()

    // Delegate all service methods to their respective commands

    override suspend fun create(account: Account, claim: String): FederationResult<CritEntity> =
        createCriticalClaimCommand.create(account, claim)

    override suspend fun delete(account: Account, id: String): FederationResult<CritEntity> =
        deleteCriticalClaimCommand.delete(account, id)

    override suspend fun findByAccount(account: Account): FederationResult<Array<CritEntity>> =
        findCriticalClaimsByAccountCommand.findByAccount(account)
}
