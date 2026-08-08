package com.sphereon.openid.fed.core.tenant

/**
 * Wasm builds do not ship admin account-http; treat as absent.
 */
actual object IdentityClasspath {
    actual fun accountModulesPresent(): Boolean = false
}
