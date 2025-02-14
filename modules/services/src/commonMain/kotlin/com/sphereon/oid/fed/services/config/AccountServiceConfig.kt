package com.sphereon.oid.fed.services.config

/**
 * Configuration class for account-related settings.
 */
class AccountServiceConfig(
    override val rootIdentifier: String = System.getenv("ROOT_IDENTIFIER") ?: "http://localhost:8080"
) : IAccountServiceConfig {
}