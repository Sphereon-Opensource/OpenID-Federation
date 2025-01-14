package com.sphereon.oid.fed.services.config

/**
 * Configuration class for account-related settings.
 */
expect class AccountServiceConfig(rootIdentifier: String = "default-root") : IAccountServiceConfig {
    override val rootIdentifier: String
}