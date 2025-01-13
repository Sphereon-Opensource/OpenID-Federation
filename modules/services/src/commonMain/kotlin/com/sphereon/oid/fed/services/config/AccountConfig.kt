package com.sphereon.oid.fed.services.config

import com.sphereon.oid.fed.common.Constants

/**
 * Configuration class for account-related settings.
 */
expect class AccountConfig() {
    val rootIdentifier: String
}