package com.sphereon.openid.fed.common.config

/**
 * Platform-agnostic environment variable access for OIDF configuration keys.
 *
 * Accepts either:
 * - An IDK property key (e.g. `oidf.federation.root.identifier`)
 * - A direct env var name (e.g. `OIDF_FEDERATION_ROOT_IDENTIFIER` or legacy `ROOT_IDENTIFIER`)
 *
 * Resolution order (JVM):
 * 1. Direct [rawGetenv] of [key]
 * 2. IDK-normalized form of [key] (`dots → underscores`, uppercase)
 * 3. Legacy SCREAMING_CASE alias via [LegacyEnvMappingPropertySource] (JVM only)
 *
 * JS/Wasm: steps 1–2 only (no legacy aliases).
 *
 * Test isolation: set [OidfEnvOverrides.map] / [OidfEnvOverrides.withEnv] so
 * lookups never touch the real process environment.
 *
 * @param key Property key or env var name
 * @return Value or null if unset
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
