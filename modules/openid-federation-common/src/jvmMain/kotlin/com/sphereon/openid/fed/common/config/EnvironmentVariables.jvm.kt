package com.sphereon.openid.fed.common.config

/**
 * JVM implementation of environment variable access.
 *
 * This implementation supports both:
 * 1. IDK-normalized environment variables (e.g., OIDF_FEDERATION_ROOT_IDENTIFIER)
 * 2. Legacy environment variables for backwards compatibility (e.g., ROOT_IDENTIFIER)
 *
 * The legacy mapping is handled by [LegacyEnvMappingPropertySource] which is JVM-only.
 *
 * @param key The environment variable name (can be IDK property key or direct env var name)
 * @return The value or null if not set
 */
actual fun getEnvironmentVariable(key: String): String? {
    // First try direct lookup (for already-normalized keys or direct env var names)
    System.getenv(key)?.let { return it }

    // Then try IDK-normalized lookup (convert property key to env var format)
    val normalizedKey = normalizeKeyForEnv(key)
    if (normalizedKey != key) {
        System.getenv(normalizedKey)?.let { return it }
    }

    // Finally, check legacy environment variable mapping (JVM-only feature)
    val legacyKey = LegacyEnvMappingPropertySource.reverseMappings[key]
    if (legacyKey != null) {
        System.getenv(legacyKey)?.let { return it }
    }

    return null
}
