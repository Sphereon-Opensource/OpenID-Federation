package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult

import com.sphereon.openid.fed.persistence.models.Crit as CritEntity
import com.sphereon.openid.fed.services.command.criticalClaim.CreateCriticalClaimArgs
import com.sphereon.openid.fed.services.command.criticalClaim.CreateCriticalClaimCommand
import com.sphereon.openid.fed.services.command.criticalClaim.DeleteCriticalClaimArgs
import com.sphereon.openid.fed.services.command.criticalClaim.DeleteCriticalClaimCommand
import com.sphereon.openid.fed.services.command.criticalClaim.FindCriticalClaimsByAccountArgs
import com.sphereon.openid.fed.services.command.criticalClaim.FindCriticalClaimsByAccountCommand
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

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
@ContributesBinding(SessionScope::class, binding = binding<CriticalClaimService>())
class CriticalClaimServiceImpl(
    private val createCriticalClaimCommand: CreateCriticalClaimCommand,
    private val deleteCriticalClaimCommand: DeleteCriticalClaimCommand,
    private val findCriticalClaimsByAccountCommand: FindCriticalClaimsByAccountCommand
) : CriticalClaimService {

    override suspend fun create(tenantId: String, claim: String): FederationResult<CritEntity> =
        createCriticalClaimCommand.execute(CreateCriticalClaimArgs(tenantId, claim)).toFederationResult()

    override suspend fun delete(tenantId: String, id: String): FederationResult<CritEntity> =
        deleteCriticalClaimCommand.execute(DeleteCriticalClaimArgs(tenantId, id)).toFederationResult()

    override suspend fun findByAccount(tenantId: String): FederationResult<Array<CritEntity>> =
        findCriticalClaimsByAccountCommand.execute(FindCriticalClaimsByAccountArgs(tenantId)).toFederationResult()
}
