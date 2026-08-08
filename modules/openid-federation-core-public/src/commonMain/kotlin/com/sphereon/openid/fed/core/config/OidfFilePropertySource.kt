package com.sphereon.openid.fed.core.config

/**
 * Lower-priority file/classpath property source for OIDF configuration.
 *
 * ## Boundary
 * This is the OIDF side of the IDK property pipeline for **file defaults**:
 * - Loaded from classpath `reference.properties` / `reference.conf` and optional
 *   `application.properties` / `application-{profile}.properties`
 * - Must **not** override environment variables or [DefaultAppMapPropertySource]
 *   programmatic keys (KMS, runtime overrides)
 *
 * Precedence is enforced in [OidfPropertyResolution] (not by map merge order alone).
 *
 * @see OidfConfigFileLoader
 * @see OidfPropertyResolution
 */
object OidfFilePropertySource {
    private val properties = linkedMapOf<String, String>()

    fun get(key: String): String? = properties[key]?.takeIf { it.isNotEmpty() }

    fun putAll(entries: Map<String, String>) {
        entries.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotEmpty()) {
                properties[k] = v
            }
        }
    }

    fun clear() {
        properties.clear()
    }

    fun snapshot(): Map<String, String> = properties.toMap()

    fun size(): Int = properties.size
}
