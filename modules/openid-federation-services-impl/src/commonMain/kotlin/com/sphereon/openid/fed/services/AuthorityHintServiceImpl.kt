package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AuthorityHint
import com.sphereon.openid.fed.services.command.authorityHint.CreateAuthorityHintCommand
import com.sphereon.openid.fed.services.command.authorityHint.DeleteAuthorityHintCommand
import com.sphereon.openid.fed.services.command.authorityHint.FindAuthorityHintsByAccountCommand
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

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
@ContributesBinding(SessionScope::class, boundType = AuthorityHintService::class)
class AuthorityHintServiceImpl(
    private val createAuthorityHintCommand: CreateAuthorityHintCommand,
    private val deleteAuthorityHintCommand: DeleteAuthorityHintCommand,
    private val findAuthorityHintsByAccountCommand: FindAuthorityHintsByAccountCommand
) : AuthorityHintService {

    /**
     * Inner class implementing the Commands interface.
     * Provides access to individual commands for advanced use cases.
     */
    inner class CommandsImpl : AuthorityHintService.Commands {
        override val createAuthorityHint: CreateAuthorityHintCommand
            get() = createAuthorityHintCommand

        override val deleteAuthorityHint: DeleteAuthorityHintCommand
            get() = deleteAuthorityHintCommand

        override val findByAccount: FindAuthorityHintsByAccountCommand
            get() = findAuthorityHintsByAccountCommand
    }

    override val commands: AuthorityHintService.Commands = CommandsImpl()

    // Delegate all service methods to their respective commands

    override suspend fun createAuthorityHint(account: Account, identifier: String): FederationResult<AuthorityHint> =
        createAuthorityHintCommand.createAuthorityHint(account, identifier)

    override suspend fun deleteAuthorityHint(account: Account, id: String): FederationResult<AuthorityHint> =
        deleteAuthorityHintCommand.deleteAuthorityHint(account, id)

    override suspend fun findByAccount(account: Account): FederationResult<List<AuthorityHint>> =
        findAuthorityHintsByAccountCommand.findByAccount(account)
}
