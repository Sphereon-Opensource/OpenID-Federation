@file:Suppress("DEPRECATION")

package com.sphereon.openid.fed.services

/**
 * Backward compatibility re-export.
 * Use [com.sphereon.openid.fed.account.AccountService] from the account-public module instead.
 */
@Deprecated(
    "Use com.sphereon.openid.fed.account.AccountService instead",
    replaceWith = ReplaceWith("AccountService", "com.sphereon.openid.fed.account.AccountService")
)
typealias AccountService = com.sphereon.openid.fed.account.AccountService
