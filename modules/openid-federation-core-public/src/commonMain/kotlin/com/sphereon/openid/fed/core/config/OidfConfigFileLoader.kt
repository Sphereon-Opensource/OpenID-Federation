package com.sphereon.openid.fed.core.config

import com.sphereon.core.api.log.Log

/**
 * Loads **OIDFed reference defaults** into [OidfFilePropertySource] (lowest file tier).
 *
 * ## YAML is IDK, not OIDFed
 * `application.yaml` / `application.yml` and tenant/principal YAML are loaded by
 * **IDK `lib-conf-yaml`** ([com.sphereon.conf.yaml.YamlFileAppPropertySource],
 * tenant/principal variants) into [com.sphereon.core.api.conf.AppConfigService] /
 * session config services via [com.sphereon.core.api.conf.PropertySourceContribution].
 * Do not add a second YAML parser here.
 *
 * ## What this loader still does
 * 1. Classpath `reference.properties` (flat packaged defaults)
 * 2. Classpath `reference.conf` (HOCON flatten — fills missing keys only)
 * 3. Optional classpath / working-dir **`application.properties`** only (legacy flat files)
 *
 * Deploy YAML belongs under IDK config location (default `./config`, env
 * `SPHEREON_CONFIG_LOCATION` / `SPHEREON_CONFIG_DIR`).
 *
 * File tier remains below env and [DefaultAppMapPropertySource] in [OidfPropertyResolution].
 */
object OidfConfigFileLoader {
    private val logger = Log.app().withTag("OidfConfigFileLoader")
    private var loaded = false

    /**
     * Idempotent load of reference defaults into [OidfFilePropertySource].
     *
     * @param profile Active profile for optional `application-{profile}.properties`
     * @param force Reload even if already loaded (tests)
     */
    fun load(profile: String = "default", force: Boolean = false) {
        if (loaded && !force) return
        if (force) OidfFilePropertySource.clear()

        mergeClasspathResource("reference.properties", overwrite = true)

        val confText = ClasspathResourceReader.readText("reference.conf")
            ?: ClasspathResourceReader.readText("com/sphereon/openid/fed/common/reference.conf")
        if (!confText.isNullOrBlank()) {
            val flattened = OidfHoconFlattener.flatten(confText)
            val missing = flattened.filterKeys { OidfFilePropertySource.get(it) == null }
            OidfFilePropertySource.putAll(missing)
            logger.info("Loaded ${missing.size} keys from reference.conf (HOCON flatten)")
        }

        // Legacy flat properties only — YAML is IDK lib-conf-yaml
        mergeClasspathResource("application.properties", overwrite = true)
        if (profile.isNotBlank() && profile != "default") {
            mergeClasspathResource("application-$profile.properties", overwrite = true)
        } else {
            mergeClasspathResource("application-default.properties", overwrite = true)
        }

        mergeWorkingFile("application.properties", overwrite = true)
        if (profile.isNotBlank()) {
            mergeWorkingFile("application-$profile.properties", overwrite = true)
        }
        mergeWorkingFile("config/application.properties", overwrite = true)

        loaded = true
        logger.info(
            "OIDF reference file property source ready: ${OidfFilePropertySource.size()} keys " +
                "(profile=$profile). YAML is loaded by IDK lib-conf-yaml on AppConfigService.",
        )
    }

    fun resetForTests() {
        loaded = false
        OidfFilePropertySource.clear()
    }

    /**
     * Tenant ids that have at least one `oidf.tenant.<id>.*` key in the reference file tier.
     * (IDK tenant YAML scopes are separate — use TenantConfigService at runtime.)
     */
    fun tenantIdsFromFileSource(): Set<String> {
        val prefix = "${OidfConfigKeys.Tenant.PREFIX}."
        return OidfFilePropertySource.snapshot().keys
            .mapNotNull { key ->
                if (!key.startsWith(prefix)) return@mapNotNull null
                val rest = key.removePrefix(prefix)
                val id = rest.substringBefore('.')
                id.takeIf { it.isNotBlank() && rest.contains('.') }
            }
            .toSet()
    }

    private fun mergeClasspathResource(name: String, overwrite: Boolean) {
        val text = ClasspathResourceReader.readText(name) ?: return
        mergeParsed(name, parsePropertiesText(text), overwrite, sourceLabel = "classpath")
    }

    private fun mergeWorkingFile(path: String, overwrite: Boolean) {
        val text = ClasspathResourceReader.readWorkingDirectoryFile(path) ?: return
        mergeParsed(path, parsePropertiesText(text), overwrite, sourceLabel = "working-dir")
    }

    private fun mergeParsed(
        name: String,
        parsed: Map<String, String>,
        overwrite: Boolean,
        sourceLabel: String,
    ) {
        if (parsed.isEmpty()) return
        if (overwrite) {
            OidfFilePropertySource.putAll(parsed)
        } else {
            OidfFilePropertySource.putAll(parsed.filterKeys { OidfFilePropertySource.get(it) == null })
        }
        logger.info("Merged $sourceLabel $name (${parsed.size} keys)")
    }

    /**
     * Minimal .properties parser (key=value, # comments).
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
 * Platform classpath / filesystem reads for reference property files.
 */
expect object ClasspathResourceReader {
    fun readText(resourceName: String): String?
    fun readWorkingDirectoryFile(path: String): String?
}
