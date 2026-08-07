package com.sphereon.openid.fed.common.config

/**
 * JS/Node.js process environment access.
 */
actual fun platformGetenv(name: String): String? {
    val value = js("process.env[name]") as? String
    return value?.takeIf { it.isNotEmpty() }
}

/**
 * JS/Node.js implementation of environment variable access.
 *
 * Uses Node.js process.env for environment variable lookup.
 * Only supports IDK-normalized environment variables (no legacy mapping).
 *
 * All reads go through [rawGetenv] so tests can isolate via [OidfEnvOverrides].
 */
actual fun getEnvironmentVariable(key: String): String? {
    rawGetenv(key)?.let { return it }

    val normalizedKey = normalizeKeyForEnv(key)
    if (normalizedKey != key) {
        rawGetenv(normalizedKey)?.let { return it }
    }

    return null
}
