package com.sphereon.openid.fed.common.config

import kotlin.concurrent.Volatile

/**
 * Test-only process-environment override for OIDF configuration.
 *
 * Production leaves [map] as `null`, so [rawGetenv] falls through to the real
 * platform environment ([platformGetenv]).
 *
 * When [map] is non-null (tests), **only** that map is consulted — no fall-through
 * to the host process env — so regression suites are isolated from the developer's
 * machine environment.
 *
 * Prefer [withEnv] over assigning [map] directly so previous overrides are restored.
 */
object OidfEnvOverrides {
    /**
     * When non-null, replaces the process environment for [rawGetenv].
     * Keys must be the actual env var names (e.g. `ROOT_IDENTIFIER`, `OIDF_DATASOURCE_URL`).
     */
    @Volatile
    var map: Map<String, String>? = null

    /**
     * Run [block] with a fully isolated fake environment.
     * Restores the previous override (or clears it) afterward.
     */
    inline fun <T> withEnv(env: Map<String, String>, block: () -> T): T {
        val previous = map
        map = env
        return try {
            block()
        } finally {
            map = previous
        }
    }

    /**
     * Clear any test override (restores real platform getenv).
     */
    fun clear() {
        map = null
    }
}

/**
 * Platform process environment access.
 *
 * JVM: IDK [com.sphereon.core.api.conf.Env] (never call System.getenv from OIDFed).
 * JS: Node `process.env`.
 * Prefer [rawGetenv] so tests can isolate via [OidfEnvOverrides].
 */
expect fun platformGetenv(name: String): String?

/**
 * Raw environment variable lookup by exact name.
 *
 * Uses [OidfEnvOverrides] when set; otherwise [platformGetenv].
 * Does **not** apply property-key normalization or legacy aliases —
 * use [getEnvironmentVariable] for the full OIDF env resolution chain.
 */
fun rawGetenv(name: String): String? {
    val override = OidfEnvOverrides.map
    return if (override != null) {
        override[name]
    } else {
        platformGetenv(name)
    }
}
