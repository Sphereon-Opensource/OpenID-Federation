package com.sphereon.openid.fed.common.config

import com.sphereon.core.api.conf.ConfigLevel
import com.sphereon.core.api.conf.PropertyKeyNormalizerImpl
import com.sphereon.core.api.conf.PropertySource
import com.sphereon.core.api.conf.RefreshablePropertySource
import com.sphereon.core.api.conf.ScopedPropertySource
import com.sphereon.di.Order
import kotlin.reflect.KClass

/**
 * IDK [PropertySource] that exposes OIDF configuration from the process environment
 * (IDK [com.sphereon.core.api.conf.Env] + legacy SCREAMING_CASE aliases).
 *
 * ## Why this exists
 * IDK's built-in [com.sphereon.core.api.conf.EnvPropertySource] normalizes
 * `OIDF_FEDERATION_ROOT_IDENTIFIER` → `oidf.federation.root.identifier`, but does **not**
 * know OIDFed legacy names such as `ROOT_IDENTIFIER` or `DATASOURCE_URL`.
 * This bridge reuses [getEnvironmentVariable] so both forms appear under the same
 * `oidf.*` keys inside [com.sphereon.core.api.conf.AppConfigService].
 *
 * ## Order
 * [Order.HIGH] — below Env [Order.HIGHEST] so pure IDK-normalized env vars win when both
 * sources can resolve the same key; still above medium/file maps so deployers using
 * legacy env vars override packaged defaults.
 *
 * ## Live reads
 * Lookups are not snapshotted at construction; each read goes through [getEnvironmentVariable]
 * / [OidfEnvOverrides] so tests and process env remain consistent.
 *
 * Register via [OidfEnvBridgePropertySourceContribution] (Metro
 * [com.sphereon.core.api.conf.PropertySourceContribution]) or [registerOn] for manual graphs.
 *
 * @see LegacyEnvMappingPropertySource
 * @see getEnvironmentVariable
 */
class OidfEnvBridgePropertySource(
    private val order: Int = Order.HIGH.orderValue,
) : ScopedPropertySource<Map<String, Any>>,
    RefreshablePropertySource {

    private val keyNormalizer = PropertyKeyNormalizerImpl.Default
    private var revision: Long = 1L

    override val isPlatformSupported: Boolean = true
    override val configLevel: ConfigLevel = ConfigLevel.APP
    override val contentRevision: Long
        get() = revision

    override fun refreshIfNeeded() {
        // Live source — bump revision so caching resolvers re-read after test env overrides.
        revision++
    }

    override fun getName(): String = NAME

    override fun getOrder(): Int = order

    override fun compareTo(other: PropertySource<*>): Int =
        this.getOrder().compareTo(other.getOrder())

    override fun hasProperty(name: String): Boolean =
        getEnvironmentVariable(name)?.isNotEmpty() == true

    override fun getPropertyAsString(name: String): String? =
        getEnvironmentVariable(name)?.takeIf { it.isNotEmpty() }

    override fun <T : Any> getProperty(
        name: String,
        targetType: KClass<T>,
    ): T? {
        val raw = getPropertyAsString(name) ?: return null
        return coerce(raw, targetType)
    }

    override fun removeProperty(name: String) {
        throw UnsupportedOperationException("Cannot remove environment-backed properties")
    }

    override fun getSource(): Map<String, Any> {
        // Snapshot of currently resolvable oidf.* / kms.* keys from env + legacy aliases.
        val snapshot = linkedMapOf<String, Any>()
        for (idkKey in knownIdkKeys()) {
            getEnvironmentVariable(idkKey)?.takeIf { it.isNotEmpty() }?.let { value ->
                snapshot[keyNormalizer.normalize(idkKey)] = value
            }
        }
        return snapshot
    }

    override fun getAllPropertyNames(): Set<String> = getSource().keys

    private fun knownIdkKeys(): Set<String> {
        val fromLegacy = LegacyEnvMappingPropertySource.legacyMappings.values.toSet()
        // Also include reverse-map keys and common oidf keys that only exist as OIDF_* form.
        return fromLegacy
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> coerce(value: String, targetType: KClass<T>): T? {
        if (targetType.isInstance(value)) return value as T
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val coerced: Any? = when (targetType) {
            String::class -> trimmed
            Boolean::class -> trimmed.lowercase() in setOf("true", "1", "yes", "on")
            Int::class -> trimmed.toIntOrNull()
            Long::class -> trimmed.toLongOrNull()
            Double::class -> trimmed.toDoubleOrNull()
            Float::class -> trimmed.toFloatOrNull()
            else -> null
        }
        return coerced as T?
    }

    companion object {
        const val NAME = "oidf-env-bridge"
        const val PROVIDER_ID = "oidf-legacy-env"

        /** Shared instance for DI and manual registration. */
        val Default: OidfEnvBridgePropertySource = OidfEnvBridgePropertySource()
    }
}
