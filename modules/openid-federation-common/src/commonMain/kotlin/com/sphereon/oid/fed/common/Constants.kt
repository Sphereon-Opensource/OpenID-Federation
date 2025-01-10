package com.sphereon.oid.fed.common

object Constants {
    // Account-related constants
    const val DEFAULT_ROOT_USERNAME = "root"
    const val ACCOUNT_HEADER = "X-Account-Username"
    const val ACCOUNT_ATTRIBUTE = "account"
    const val ACCOUNT_IDENTIFIER_ATTRIBUTE = "accountIdentifier"

    // Error messages
    const val ACCOUNT_ALREADY_EXISTS = "Account already exists"
    const val ACCOUNT_NOT_FOUND = "Account not found"
    const val ROOT_IDENTIFIER_NOT_SET = "Root identifier not set"
    const val ROOT_ACCOUNT_CANNOT_BE_DELETED = "Root account cannot be deleted"

    // Authority Hint related messages
    const val AUTHORITY_HINT_ALREADY_EXISTS = "Authority hint already exists"
    const val AUTHORITY_HINT_NOT_FOUND = "Authority hint not found"
    const val FAILED_TO_CREATE_AUTHORITY_HINT = "Failed to create authority hint"
    const val FAILED_TO_DELETE_AUTHORITY_HINT = "Failed to delete authority hint"
}
