package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult

import com.sphereon.openid.fed.openapi.models.AuthorityHint
import com.sphereon.openid.fed.services.command.authorityHint.CreateAuthorityHintArgs
import com.sphereon.openid.fed.services.command.authorityHint.CreateAuthorityHintCommand
import com.sphereon.openid.fed.services.command.authorityHint.DeleteAuthorityHintArgs
import com.sphereon.openid.fed.services.command.authorityHint.DeleteAuthorityHintCommand
import com.sphereon.openid.fed.services.command.authorityHint.FindAuthorityHintsByAccountArgs
import com.sphereon.openid.fed.services.command.authorityHint.FindAuthorityHintsByAccountCommand
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of AuthorityHintService as a command aggregator.
 *
 * This service aggregates all authority hint-related commands and provides both
 * direct method access and command-based access for advanced use cases.
 *
 * All methods delegate to individual commands, enabling:
 * - Command composition and chaining
 * - Cross-cutting concerns via command extensions (audit, caching, auth)
 * - Testability through command mocking
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<AuthorityHintService>())
class AuthorityHintServiceImpl(
    private val createAuthorityHintCommand: CreateAuthorityHintCommand,
    private val deleteAuthorityHintCommand: DeleteAuthorityHintCommand,
    private val findAuthorityHintsByAccountCommand: FindAuthorityHintsByAccountCommand
) : AuthorityHintService {

    override suspend fun createAuthorityHint(tenantId: String, identifier: String): FederationResult<AuthorityHint> =
        createAuthorityHintCommand.execute(CreateAuthorityHintArgs(tenantId, identifier)).toFederationResult()

    override suspend fun deleteAuthorityHint(tenantId: String, id: String): FederationResult<AuthorityHint> =
        deleteAuthorityHintCommand.execute(DeleteAuthorityHintArgs(tenantId, id)).toFederationResult()

    override suspend fun findByAccount(tenantId: String): FederationResult<List<AuthorityHint>> =
        findAuthorityHintsByAccountCommand.execute(FindAuthorityHintsByAccountArgs(tenantId)).toFederationResult()
}
