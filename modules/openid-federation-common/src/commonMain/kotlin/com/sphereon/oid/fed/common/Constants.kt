package com.sphereon.oid.fed.common

object Constants {
    // Account-related constants
    const val DEFAULT_ROOT_USERNAME = "root"
    const val ACCOUNT_HEADER = "X-Account-Username"
    const val ACCOUNT_ATTRIBUTE = "account"
    const val ACCOUNT_IDENTIFIER_ATTRIBUTE = "accountIdentifier"

    // Persistence-related constants
    const val DATASOURCE_URL = "DATASOURCE_URL"
    const val DATASOURCE_USER = "DATASOURCE_USER"
    const val DATASOURCE_PASSWORD = "DATASOURCE_PASSWORD"
    const val SQLITE_IS_NOT_SUPPORTED_IN_JVM = "SQLite is not supported in JVM"

    // Error messages
    const val ACCOUNT_ALREADY_EXISTS = "Account already exists"
    const val ACCOUNT_NOT_FOUND = "Account not found"
    const val KEY_NOT_FOUND = "Key not found"
    const val KEY_ALREADY_REVOKED = "Key already revoked"
    const val SUBORDINATE_ALREADY_EXISTS = "Subordinate already exists"
    const val ENTITY_CONFIGURATION_METADATA_ALREADY_EXISTS = "Entity configuration metadata already exists"
    const val FAILED_TO_CREATE_ENTITY_CONFIGURATION_METADATA = "Failed to create entity configuration metadata"
    const val FAILED_TO_CREATE_SUBORDINATE_METADATA = "Failed to create subordinate metadata"
    const val ENTITY_CONFIGURATION_METADATA_NOT_FOUND = "Entity configuration metadata not found"
    const val FAILED_TO_CREATE_AUTHORITY_HINT = "Failed to create authority hint"
    const val AUTHORITY_HINT_NOT_FOUND = "Authority hint not found"
    const val FAILED_TO_DELETE_AUTHORITY_HINT = "Failed to delete authority hint"
    const val AUTHORITY_HINT_ALREADY_EXISTS = "Authority hint already exists"
    const val CRIT_ALREADY_EXISTS = "Crit already exists"
    const val FAILED_TO_CREATE_CRIT = "Failed to create crit"
    const val FAILED_TO_DELETE_CRIT = "Failed to delete crit"
    const val NO_KEYS_FOUND = "No keys found"
    const val SUBORDINATE_NOT_FOUND = "Subordinate not found"
    const val SUBORDINATE_JWK_NOT_FOUND = "Subordinate JWK not found"
    const val SUBORDINATE_STATEMENT_NOT_FOUND = "Subordinate statement not found"
    const val SUBORDINATE_METADATA_NOT_FOUND = "Subordinate metadata not found"
    const val SUBORDINATE_METADATA_ALREADY_EXISTS = "Subordinate metadata already exists"
    const val ROOT_IDENTIFIER_NOT_SET = "Root identifier not set"
    const val ROOT_ACCOUNT_CANNOT_BE_DELETED = "Root account cannot be deleted"
}
