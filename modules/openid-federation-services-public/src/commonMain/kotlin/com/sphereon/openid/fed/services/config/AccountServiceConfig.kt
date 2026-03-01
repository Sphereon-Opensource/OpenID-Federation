@file:Suppress("DEPRECATION")

package com.sphereon.openid.fed.services.config

/**
 * Backward compatibility re-export.
 * Use [com.sphereon.openid.fed.account.config.AccountServiceConfig] instead.
 */
@Deprecated(
    "Use com.sphereon.openid.fed.account.config.AccountServiceConfig instead",
    replaceWith = ReplaceWith("AccountServiceConfig", "com.sphereon.openid.fed.account.config.AccountServiceConfig")
)
typealias AccountServiceConfig = com.sphereon.openid.fed.account.config.AccountServiceConfig
