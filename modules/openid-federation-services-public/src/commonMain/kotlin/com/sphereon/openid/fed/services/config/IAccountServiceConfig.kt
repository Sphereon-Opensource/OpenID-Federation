@file:Suppress("DEPRECATION")

package com.sphereon.openid.fed.services.config

/**
 * Backward compatibility re-export.
 * Use [com.sphereon.openid.fed.account.config.IAccountServiceConfig] instead.
 */
@Deprecated(
    "Use com.sphereon.openid.fed.account.config.IAccountServiceConfig instead",
    replaceWith = ReplaceWith("IAccountServiceConfig", "com.sphereon.openid.fed.account.config.IAccountServiceConfig")
)
typealias IAccountServiceConfig = com.sphereon.openid.fed.account.config.IAccountServiceConfig
