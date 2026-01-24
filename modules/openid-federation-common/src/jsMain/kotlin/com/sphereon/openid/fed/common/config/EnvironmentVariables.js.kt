package com.sphereon.openid.fed.common.config

/**
 * JS/Node.js implementation of environment variable access.
 *
 * Uses Node.js process.env for environment variable lookup.
 * Only supports IDK-normalized environment variables (no legacy mapping).
 *
 * @param key The environment variable name (can be IDK property key or direct env var name)
 * @return The value or null if not set
 */
actual fun getEnvironmentVariable(key: String): String? {
    // First try direct lookup
    val directValue = js("process.env[key]") as? String
    if (!directValue.isNullOrEmpty()) {
        return directValue
    }

    // Then try IDK-normalized lookup (convert property key to env var format)
    val normalizedKey = normalizeKeyForEnv(key)
    if (normalizedKey != key) {
        val normalizedValue = js("process.env[normalizedKey]") as? String
        if (!normalizedValue.isNullOrEmpty()) {
            return normalizedValue
        }
    }

    return null
}
