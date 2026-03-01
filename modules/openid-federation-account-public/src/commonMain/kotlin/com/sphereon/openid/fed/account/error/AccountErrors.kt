package com.sphereon.openid.fed.account.error

/**
 * Account-specific error constants.
 */
object AccountConstants {
    const val ACCOUNT_HEADER = "X-Account-Username"
    const val ACCOUNT_ATTRIBUTE = "account"
    const val ACCOUNT_IDENTIFIER_ATTRIBUTE = "accountIdentifier"
    const val ACCOUNT_ALREADY_EXISTS = "Account already exists"
    const val ACCOUNT_NOT_FOUND = "Account not found"
    const val ROOT_ACCOUNT_CANNOT_BE_DELETED = "Root account cannot be deleted"
}
