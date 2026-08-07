package com.sphereon.openid.fed.common.config

import com.sphereon.core.api.conf.Env

/**
 * JVM process environment access via IDK [Env] (not [System.getenv] directly).
 *
 * IDK's JVM [Env] reads process environment; commercial hosts can supply alternate
 * Env implementations. OIDFed product code must not call System.getenv.
 */
actual fun platformGetenv(name: String): String? = Env.get(name)

/**
 * JVM implementation of environment variable access for the config pipeline.
 *
 * Supports both:
 * 1. IDK-normalized environment variables (e.g., OIDF_FEDERATION_ROOT_IDENTIFIER)
 * 2. Legacy environment variables for backwards compatibility (e.g., ROOT_IDENTIFIER)
 *
 * All reads go through [rawGetenv] so tests can isolate via [OidfEnvOverrides].
 * Callers should prefer [com.sphereon.openid.fed.core.config.OidfConfigBinder] /
 * [com.sphereon.openid.fed.core.config.OidfPropertyResolution] over this function.
 */
actual fun getEnvironmentVariable(key: String): String? {
    // First try direct lookup (for already-normalized keys or direct env var names)
    rawGetenv(key)?.let { return it }

    // Then try IDK-normalized lookup (convert property key to env var format)
    val normalizedKey = normalizeKeyForEnv(key)
    if (normalizedKey != key) {
        rawGetenv(normalizedKey)?.let { return it }
    }

    // Finally, check all legacy / alias env vars for this IDK key (JVM-only)
    for (legacyKey in LegacyEnvMappingPropertySource.legacyAliasesFor(key)) {
        rawGetenv(legacyKey)?.let { return it }
    }

    return null
}
