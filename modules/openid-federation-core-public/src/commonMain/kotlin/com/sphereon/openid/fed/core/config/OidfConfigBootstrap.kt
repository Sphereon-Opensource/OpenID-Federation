package com.sphereon.openid.fed.core.config

import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.core.api.conf.DefaultPrincipalMapPropertySource
import com.sphereon.core.api.log.Log

/**
 * Seeds IDK property maps and loads OIDF file-based defaults at process start.
 *
 * ## Why this exists
 * Call once before AppGraph creation (admin and federation servers) so **configuration**
 * ([OidfConfigBinder] / [OidfPropertyResolution]) and **KeyManagerService** share the same
 * IDK property pipeline instead of each server main stuffing ad-hoc maps independently.
 *
 * ## What is seeded where (developer boundary)
 *
 * | Data | Destination | Why |
 * |------|-------------|-----|
 * | File defaults (`reference.*`, `application.*`) | [OidfFilePropertySource] | Below env so deployers can override |
 * | Software KMS `kms.providers.*` | [DefaultAppMapPropertySource] + principal map | Session KeyManagerService (IDK KmsKtor pattern) |
 * | Hardcoded [OidfConfigDefaults] | Not copied into AppMap | Lowest tier in [OidfPropertyResolution] only |
 *
 * **Do not** dump all oidf.* defaults into [DefaultAppMapPropertySource] — that would
 * shadow environment variables (env is below AppMap in the resolver).
 *
 * ## KMS property boundary
 * Mirrors IDK Ktor KMS tests:
 * - Un-namespaced `kms.providers.*` / `kms.keystores.*` on app **and** principal maps
 * - Namespaced `{appId}.{profile}.kms.*` for binders expecting appId.profile
 * - Only fill **missing** keys so env/deployer pre-loads win
 *
 * @see OidfConfigDefaults.defaultSoftwareKmsProperties
 * @see OidfConfigFileLoader
 * @see OidfPropertyResolution
 */
object OidfConfigBootstrap {
    private val logger = Log.app().withTag("OidfConfigBootstrap")
    private val seeded = mutableSetOf<String>()

    /**
     * Seed file config + KMS maps for a server process.
     *
     * @param appId Application id for namespaced KMS keys — **must match** AppGraph appId
     * @param profile Profile segment (default `"default"`)
     * @param loadFileDefaults When true, load reference/application files into [OidfFilePropertySource]
     * @param seedSoftwareKms When true, configure default software/memory KMS on app+principal maps
     */
    fun seed(
        appId: String,
        profile: String = "default",
        loadFileDefaults: Boolean = true,
        seedSoftwareKms: Boolean = true,
    ) {
        val key = "$appId:$profile"
        if (!seeded.add(key)) {
            logger.debug("Config already seeded for $key")
            return
        }

        // --- File / classpath defaults (below env) ---
        if (loadFileDefaults) {
            OidfConfigFileLoader.load(profile = profile)
        }

        // --- Software KMS (IDK app + principal maps; not oidf.* defaults) ---
        if (seedSoftwareKms) {
            val namespace = "$appId.$profile"
            val kmsProps = OidfConfigDefaults.defaultSoftwareKmsProperties(namespace)
            val toAdd = kmsProps.filterKeys { propKey ->
                DefaultAppMapPropertySource.getPropertyAsString(propKey).isNullOrEmpty()
            }
            if (toAdd.isNotEmpty()) {
                DefaultAppMapPropertySource.addProperties(toAdd)
                val principalProps = toAdd.filterKeys { !it.startsWith("$namespace.") }
                DefaultPrincipalMapPropertySource.addProperties(principalProps)
                logger.info(
                    "Seeded default software KMS provider " +
                        "(app map ${toAdd.size} keys, principal map ${principalProps.size} keys)",
                )
            }
        }
    }

    /**
     * Test helper to allow re-seeding after clearing property sources.
     * Not for production use.
     */
    fun resetForTests() {
        seeded.clear()
        OidfConfigFileLoader.resetForTests()
    }
}
