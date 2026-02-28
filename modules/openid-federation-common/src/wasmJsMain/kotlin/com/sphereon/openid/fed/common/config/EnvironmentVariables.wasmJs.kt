package com.sphereon.openid.fed.common.config

/**
 * WasmJS implementation of environment variable access.
 *
 * Uses Node.js process.env for environment variable lookup via WasmJS js-interop.
 * Only supports IDK-normalized environment variables (no legacy mapping).
 *
 * @param key The environment variable name (can be IDK property key or direct env var name)
 * @return The value or null if not set
 */
actual fun getEnvironmentVariable(key: String): String? {
    // First try direct lookup
    val directValue = getProcessEnvVar(key)
    if (!directValue.isNullOrEmpty()) {
        return directValue
    }

    // Then try IDK-normalized lookup (convert property key to env var format)
    val normalizedKey = normalizeKeyForEnv(key)
    if (normalizedKey != key) {
        val normalizedValue = getProcessEnvVar(normalizedKey)
        if (!normalizedValue.isNullOrEmpty()) {
            return normalizedValue
        }
    }

    return null
}

private fun getProcessEnvVarJs(key: String): JsAny? =
    js("(typeof process !== 'undefined' && process.env && process.env[key] !== undefined) ? String(process.env[key]) : null")

private fun getProcessEnvVar(key: String): String? =
    getProcessEnvVarJs(key)?.toString()
