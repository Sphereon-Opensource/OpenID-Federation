package com.sphereon.openid.fed.core.tenant

/**
 * Detects whether optional **account** packaging modules are present.
 *
 * Primary marker: `openid-federation-account-http` (LEGACY `/accounts` REST).
 * When present on the classpath, greenfield installs default to [IdentityMode.ACCOUNT]
 * so all-in-one open-source packaging keeps standalone multi-entity UX.
 * Platform hosts omit that jar and get [IdentityMode.EXTERNAL] for new installs.
 */
expect object IdentityClasspath {
    /**
     * True when account management modules suitable for ACCOUNT mode are loadable.
     */
    fun accountModulesPresent(): Boolean
}
