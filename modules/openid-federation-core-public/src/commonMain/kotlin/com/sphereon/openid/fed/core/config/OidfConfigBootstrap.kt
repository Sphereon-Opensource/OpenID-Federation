package com.sphereon.openid.fed.core.config

import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.core.api.conf.DefaultPrincipalMapPropertySource
import com.sphereon.core.api.conf.PropertySourceBootstrap
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
 * | Reference defaults (`reference.properties` / `.conf`) | [OidfFilePropertySource] | Below env; **not** application.yaml |
 * | `application.yaml` (+ tenant/principal YAML) | **IDK `lib-conf-yaml`** on AppConfigService | Registered via PropertySourceContribution |
 * | Software KMS `kms.providers.*` | [DefaultAppMapPropertySource] + principal map | Session KeyManagerService (IDK KmsKtor pattern) |
 * | Hardcoded [OidfConfigDefaults] | Not copied into AppMap | Lowest tier in [OidfPropertyResolution] only |
 * | Legacy + OIDF env bridge | IDK [PropertySourceBootstrap] / `oidf-legacy-env` contribution | [ensurePropertySourcesRegistered] after AppGraph init |
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
 * ## Property source contributions
 * [AbstractAppGraph.initRootScopeProvider] already calls [PropertySourceBootstrap.registerAppSources]
 * when the graph implements [PropertySourceBootstrap.Graph]. Call
 * [ensurePropertySourcesRegistered] after graph init so servers always register
 * (including [OidfEnvBridgePropertySourceContribution]) even if the cast path changes.
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

        if (!OidfConfigSources.isEnvironmentInstalled()) {
            logger.warn(
                "Environment config source not installed yet — call OidfConfigEnvironment.install() " +
                    "before seed (servers/fixtures do this). Env-backed keys will be empty until install.",
            )
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
     * Register IDK [PropertySourceContribution]s (env bridge, cloud sources, …) on the app graph.
     *
     * Safe to call multiple times — [PropertySourceBootstrap.registerAppSources] is idempotent
     * per provider id. Prefer calling after [com.sphereon.di.app.AbstractAppGraph.initRootScopeProvider].
     *
     * @param graph App graph instance (typically AdminServerAppGraph / FederationServerAppGraph)
     * @return Number of APP sources registered after the call, or -1 if graph has no bootstrap
     */
    fun ensurePropertySourcesRegistered(graph: Any): Int {
        val bootstrap = (graph as? PropertySourceBootstrap.Graph)?.propertySourceBootstrap
        if (bootstrap == null) {
            logger.warn(
                "App graph does not expose PropertySourceBootstrap.Graph — " +
                    "OidfEnvBridgePropertySourceContribution will not be registered into AppConfigService",
            )
            return -1
        }
        bootstrap.registerAppSources()
        val count = bootstrap.registeredAppSourceCount
        logger.info(
            "Property source contributions registered (APP sources on graph: $count; " +
                "contributions known: ${bootstrap.contributions.size})",
        )
        // Bind AppConfigService for process-wide resolution (DatabaseConfig, etc.)
        val appConfig = (graph as? com.sphereon.core.api.conf.AppConfigService.Graph)?.appConfigService
        if (appConfig != null) {
            OidfConfigSources.bindAppConfig(appConfig)
            logger.info("Bound AppConfigService into OidfConfigSources for process-wide resolution")
        }
        return count
    }

    /**
     * Test helper to allow re-seeding after clearing property sources.
     * Not for production use.
     */
    fun resetForTests() {
        seeded.clear()
        OidfConfigFileLoader.resetForTests()
        OidfConfigSources.resetForTests()
    }
}
