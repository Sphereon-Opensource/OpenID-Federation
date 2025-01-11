package com.sphereon.oid.fed.services.config

/**
 * JVM implementation of account configuration.
 */
actual class AccountConfig actual constructor() {
    actual val rootIdentifier: String
        get() = System.getProperty("sphereon.federation.root-identifier")
}