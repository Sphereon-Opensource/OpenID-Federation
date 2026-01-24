package com.sphereon.openid.fed.common.config

/**
 * Platform-agnostic environment variable access.
 *
 * Each platform provides its own implementation:
 * - JVM: Uses System.getenv() with legacy env var mapping support
 * - JS: Uses process.env (Node.js)
 * - Native: Uses platform-specific APIs
 *
 * @param key The environment variable name
 * @return The value or null if not set
 */
expect fun getEnvironmentVariable(key: String): String?

/**
 * Normalize a property key for environment variable lookup.
 * Converts dots to underscores and makes uppercase.
 *
 * e.g., "oidf.federation.root.identifier" -> "OIDF_FEDERATION_ROOT_IDENTIFIER"
 */
fun normalizeKeyForEnv(key: String): String {
    return key.replace(".", "_").uppercase()
}
