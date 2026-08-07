package com.sphereon.openid.fed.account.http.di

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.account.http.command.CreateAccountEndpointCommand
import com.sphereon.openid.fed.account.http.command.DeleteAccountEndpointCommand
import com.sphereon.openid.fed.account.http.command.ListAccountsEndpointCommand
import com.sphereon.openid.fed.server.admin.api.http.AdminAccountDescriptorContribution
import com.sphereon.openid.fed.server.admin.api.http.AdminAccountEndpointContribution
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * Session-scoped contribution of LEGACY `/accounts` endpoint commands.
 * Only present when `openid-federation-account-http` is on the classpath.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesIntoSet(SessionScope::class, binding = binding<AdminAccountEndpointContribution>())
class AccountAdminEndpointContributionImpl(
    listAccountsEndpoint: ListAccountsEndpointCommand,
    createAccountEndpoint: CreateAccountEndpointCommand,
    deleteAccountEndpoint: DeleteAccountEndpointCommand,
) : AdminAccountEndpointContribution {
    override val endpointCommands: List<HttpEndpointCommand> = listOf(
        listAccountsEndpoint,
        createAccountEndpoint,
        deleteAccountEndpoint,
    )
}

/**
 * App-scoped OpenAPI / adapter descriptors for LEGACY account REST.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<AdminAccountDescriptorContribution>())
class AccountAdminDescriptorContributionImpl : AdminAccountDescriptorContribution {
    override val endpointDescriptors: List<HttpEndpointDescriptor> = listOf(
        ListAccountsEndpointCommand.ENDPOINT,
        CreateAccountEndpointCommand.ENDPOINT,
        DeleteAccountEndpointCommand.ENDPOINT,
    )
}
