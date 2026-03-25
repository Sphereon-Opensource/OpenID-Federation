package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult

import com.sphereon.openid.fed.openapi.models.ResolveResponse
import com.sphereon.openid.fed.services.command.resolution.GetSignedResolveResponseJwtArgs
import com.sphereon.openid.fed.services.command.resolution.GetSignedResolveResponseJwtCommand
import com.sphereon.openid.fed.services.command.resolution.ResolveEntityArgs
import com.sphereon.openid.fed.services.command.resolution.ResolveEntityCommand
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of ResolutionService as a command aggregator.
 *
 * This service aggregates all resolution-related commands and provides both
 * direct method access and command-based access for advanced use cases.
 *
 * All methods delegate to individual commands, enabling:
 * - Command composition and chaining
 * - Cross-cutting concerns via command extensions (audit, caching, auth)
 * - Testability through command mocking
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ResolutionService>())
class ResolutionServiceImpl(
    private val resolveEntityCommand: ResolveEntityCommand,
    private val getSignedResolveResponseJwtCommand: GetSignedResolveResponseJwtCommand
) : ResolutionService {

    override suspend fun resolveEntity(
        tenantId: String,
        sub: String,
        trustAnchor: String,
        entityTypes: Array<String>?
    ): FederationResult<ResolveResponse> =
        resolveEntityCommand.execute(ResolveEntityArgs(tenantId, sub, trustAnchor, entityTypes)).toFederationResult()

    override suspend fun getSignedResolveResponseJwt(
        tenantId: String,
        sub: String,
        trustAnchor: String,
        entityTypes: Array<String>?
    ): FederationResult<String> =
        getSignedResolveResponseJwtCommand.execute(GetSignedResolveResponseJwtArgs(tenantId, sub, trustAnchor, entityTypes)).toFederationResult()
}
