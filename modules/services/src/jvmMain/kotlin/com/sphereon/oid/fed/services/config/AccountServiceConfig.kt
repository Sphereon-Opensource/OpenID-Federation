package com.sphereon.oid.fed.services.config

/**
 * JVM implementation of account configuration.
 */
actual class AccountServiceConfig actual constructor(
    actual override val rootIdentifier: String
) : IAccountServiceConfig