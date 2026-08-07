package com.sphereon.openid.fed.core.config

import com.sphereon.core.api.log.Log

/**
 * Loads OIDF configuration files into [OidfFilePropertySource].
 *
 * ## Load order (later wins within the file tier)
 * 1. Classpath `reference.properties` (flat defaults)
 * 2. Classpath `reference.conf` (HOCON flatten — fills missing keys only)
 * 3. Classpath `application.properties`
 * 4. Classpath `application-{profile}.properties`
 * 5. Working-directory `application.properties` / `application-{profile}.properties` (if present)
 *
 * File tier is still below env and [DefaultAppMapPropertySource] (see [OidfPropertyResolution]).
 *
 * Platform note: classpath/file IO is provided via [ClasspathResourceReader] (expect/actual).
 */
object OidfConfigFileLoader {
    private val logger = Log.app().withTag("OidfConfigFileLoader")
    private var loaded = false

    /**
     * Idempotent load into [OidfFilePropertySource].
     *
     * @param profile Active profile for `application-{profile}.properties` (default `default`)
     * @param force Reload even if already loaded (tests)
     */
    fun load(profile: String = "default", force: Boolean = false) {
        if (loaded && !force) return
        if (force) OidfFilePropertySource.clear()

        // 1) Flat reference.properties
        mergeClasspath("reference.properties", overwrite = true)

        // 2) HOCON reference.conf — only fill keys not already set by properties
        val confText = ClasspathResourceReader.readText("reference.conf")
            ?: ClasspathResourceReader.readText("com/sphereon/openid/fed/common/reference.conf")
        if (!confText.isNullOrBlank()) {
            val flattened = OidfHoconFlattener.flatten(confText)
            val missing = flattened.filterKeys { OidfFilePropertySource.get(it) == null }
            OidfFilePropertySource.putAll(missing)
            logger.info("Loaded ${missing.size} keys from reference.conf (HOCON flatten)")
        }

        // 3–4) application properties (classpath)
        mergeClasspath("application.properties", overwrite = true)
        if (profile.isNotBlank() && profile != "default") {
            mergeClasspath("application-$profile.properties", overwrite = true)
        } else {
            mergeClasspath("application-default.properties", overwrite = true)
        }

        // 5) Working directory overrides
        mergeFile("application.properties", overwrite = true)
        mergeFile("application-$profile.properties", overwrite = true)

        loaded = true
        logger.info(
            "OIDF file property source ready: ${OidfFilePropertySource.size()} keys " +
                "(profile=$profile)",
        )
    }

    fun resetForTests() {
        loaded = false
        OidfFilePropertySource.clear()
    }

    private fun mergeClasspath(name: String, overwrite: Boolean) {
        val text = ClasspathResourceReader.readText(name) ?: return
        val parsed = parsePropertiesText(text)
        if (overwrite) {
            OidfFilePropertySource.putAll(parsed)
        } else {
            OidfFilePropertySource.putAll(parsed.filterKeys { OidfFilePropertySource.get(it) == null })
        }
        if (parsed.isNotEmpty()) {
            logger.debug("Merged classpath $name (${parsed.size} keys)")
        }
    }

    private fun mergeFile(path: String, overwrite: Boolean) {
        val text = ClasspathResourceReader.readWorkingDirectoryFile(path) ?: return
        val parsed = parsePropertiesText(text)
        if (overwrite) {
            OidfFilePropertySource.putAll(parsed)
        } else {
            OidfFilePropertySource.putAll(parsed.filterKeys { OidfFilePropertySource.get(it) == null })
        }
        if (parsed.isNotEmpty()) {
            logger.info("Merged working-dir $path (${parsed.size} keys)")
        }
    }

    /**
     * Minimal .properties parser (key=value, # comments). Sufficient for OIDF defaults.
     */
    fun parsePropertiesText(text: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
            val idx = line.indexOf('=').takeIf { it > 0 } ?: line.indexOf(':').takeIf { it > 0 } ?: continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }
}

/**
 * Platform classpath / filesystem reads for config files.
 */
expect object ClasspathResourceReader {
    fun readText(resourceName: String): String?
    fun readWorkingDirectoryFile(path: String): String?
}
