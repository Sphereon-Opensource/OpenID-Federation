@file:Suppress("DEPRECATION")

package com.sphereon.openid.fed.server.admin.api.http.command

/**
 * Backward compatibility re-exports.
 * Use interfaces from [com.sphereon.openid.fed.account.http.command] instead.
 */

@Deprecated(
    "Use com.sphereon.openid.fed.account.http.command.ListAccountsEndpointCommand instead",
    replaceWith = ReplaceWith("ListAccountsEndpointCommand", "com.sphereon.openid.fed.account.http.command.ListAccountsEndpointCommand")
)
typealias ListAccountsEndpointCommand = com.sphereon.openid.fed.account.http.command.ListAccountsEndpointCommand

@Deprecated(
    "Use com.sphereon.openid.fed.account.http.command.CreateAccountEndpointCommand instead",
    replaceWith = ReplaceWith("CreateAccountEndpointCommand", "com.sphereon.openid.fed.account.http.command.CreateAccountEndpointCommand")
)
typealias CreateAccountEndpointCommand = com.sphereon.openid.fed.account.http.command.CreateAccountEndpointCommand

@Deprecated(
    "Use com.sphereon.openid.fed.account.http.command.DeleteAccountEndpointCommand instead",
    replaceWith = ReplaceWith("DeleteAccountEndpointCommand", "com.sphereon.openid.fed.account.http.command.DeleteAccountEndpointCommand")
)
typealias DeleteAccountEndpointCommand = com.sphereon.openid.fed.account.http.command.DeleteAccountEndpointCommand
