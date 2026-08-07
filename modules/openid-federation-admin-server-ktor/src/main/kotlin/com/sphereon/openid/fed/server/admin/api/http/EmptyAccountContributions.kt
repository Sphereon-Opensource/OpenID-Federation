package com.sphereon.openid.fed.server.admin.api.http

import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ElementsIntoSet
import dev.zacsweers.metro.Provides

/**
 * Declares empty multibindings for optional account REST contributions.
 *
 * When `account-http` is on the classpath it contributes real elements via
 * [ContributesIntoSet]; when that jar is not a dependency the sets stay empty
 * so Metro can still inject `Set<…>`. Presence of the module **is** the switch.
 */
@ContributesTo(SessionScope::class)
interface EmptyAdminAccountEndpointContributions {
    @Provides
    @ElementsIntoSet
    fun emptyAccountEndpointContributions(): Set<AdminAccountEndpointContribution> = emptySet()
}

@ContributesTo(AppScope::class)
interface EmptyAdminAccountDescriptorContributions {
    @Provides
    @ElementsIntoSet
    fun emptyAccountDescriptorContributions(): Set<AdminAccountDescriptorContribution> = emptySet()
}
