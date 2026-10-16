package com.sphereon.openid.fed.server.admin.ktor.renewal

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.services.command.renewal.RenewDueStatementsCommand
import dev.zacsweers.metro.ContributesTo

/**
 * Typed access to the renewal command for the admin server's scheduler, which runs outside any request. Contributed
 * here rather than by the services module so only hosts that schedule renewal must satisfy its bindings.
 */
@ContributesTo(SessionScope::class)
interface StatementRenewalSessionGraph {
    val renewDueStatementsCommand: RenewDueStatementsCommand
}
