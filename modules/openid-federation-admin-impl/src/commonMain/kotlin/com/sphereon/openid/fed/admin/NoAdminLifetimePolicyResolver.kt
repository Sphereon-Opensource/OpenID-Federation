package com.sphereon.openid.fed.admin

import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.*

/** Commercial hosts replace this binding; standalone operation has no external profile policy. */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<AdminLifetimePolicyResolver>())
class NoAdminLifetimePolicyResolver : AdminLifetimePolicyResolver {
    override suspend fun resolve(scope: AdminNodeScope, key: AdminDocumentKey): AdminLifetimePolicyContext? = null
}
