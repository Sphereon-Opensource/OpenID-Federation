package com.sphereon.openid.fed.services.config

import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.openid.fed.client.config.AppConfigOidfConfigBinder
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * Federation-server Metro contribution of [AppConfigOidfConfigBinder].
 *
 * Wallet composition roots must not depend on this module; they provide
 * [AppConfigOidfConfigBinder] on the graph themselves.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<OidfConfigBinder>())
class OidfConfigBinderImpl(
    appConfigService: AppConfigService,
) : AppConfigOidfConfigBinder(appConfigService)
