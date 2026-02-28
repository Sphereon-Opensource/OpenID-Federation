package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.ResolveResponse
import com.sphereon.openid.fed.services.command.resolution.GetSignedResolveResponseJwtArgs
import com.sphereon.openid.fed.services.command.resolution.GetSignedResolveResponseJwtCommand
import com.sphereon.openid.fed.services.command.resolution.ResolveEntityArgs
import com.sphereon.openid.fed.services.command.resolution.ResolveEntityCommand
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

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
@ContributesBinding(SessionScope::class, boundType = ResolutionService::class)
class ResolutionServiceImpl(
    private val resolveEntityCommand: ResolveEntityCommand,
    private val getSignedResolveResponseJwtCommand: GetSignedResolveResponseJwtCommand
) : ResolutionService {

    override suspend fun resolveEntity(
        account: Account,
        sub: String,
        trustAnchor: String,
        entityTypes: Array<String>?
    ): FederationResult<ResolveResponse> =
        resolveEntityCommand.execute(ResolveEntityArgs(account, sub, trustAnchor, entityTypes)).toFederationResult()

    override suspend fun getSignedResolveResponseJwt(
        account: Account,
        sub: String,
        trustAnchor: String,
        entityTypes: Array<String>?
    ): FederationResult<String> =
        getSignedResolveResponseJwtCommand.execute(GetSignedResolveResponseJwtArgs(account, sub, trustAnchor, entityTypes)).toFederationResult()
}
