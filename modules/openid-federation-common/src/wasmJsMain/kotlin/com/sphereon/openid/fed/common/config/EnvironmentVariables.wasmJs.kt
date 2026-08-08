package com.sphereon.openid.fed.common.config

/**
 * Wasm/JS process environment access (limited — no real process.env on all hosts).
 */
actual fun platformGetenv(name: String): String? = null

/**
 * Wasm/JS implementation — no process environment by default.
 * Tests may still inject values via [OidfEnvOverrides].
 */
actual fun getEnvironmentVariable(key: String): String? {
    rawGetenv(key)?.let { return it }

    val normalizedKey = normalizeKeyForEnv(key)
    if (normalizedKey != key) {
        rawGetenv(normalizedKey)?.let { return it }
    }

    return null
}
