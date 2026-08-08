@file:Suppress("DEPRECATION")

package com.sphereon.openid.fed.services

/**
 * Backward compatibility re-export.
 * Use [com.sphereon.openid.fed.account.AccountServiceImpl] from the account-impl module instead.
 */
@Deprecated(
    "Use com.sphereon.openid.fed.account.AccountServiceImpl instead",
    replaceWith = ReplaceWith("AccountServiceImpl", "com.sphereon.openid.fed.account.AccountServiceImpl")
)
typealias AccountServiceImpl = com.sphereon.openid.fed.account.AccountServiceImpl
