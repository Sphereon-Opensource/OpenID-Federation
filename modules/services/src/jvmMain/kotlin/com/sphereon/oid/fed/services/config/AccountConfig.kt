package com.sphereon.oid.fed.services.config

import com.sphereon.oid.fed.common.Constants

/**
 * JVM implementation of account configuration.
 */
actual class AccountConfig actual constructor() {
    actual val rootIdentifier: String
        get() = System.getenv("ROOT_IDENTIFIER") ?: "http://localhost:8081"  // Default value for tests
}