package com.sphereon.openid.fed.services.config

import com.sphereon.core.api.conf.ConfigLevel
import com.sphereon.core.api.conf.ConfigService
import com.sphereon.core.api.conf.PropertyResolver
import com.sphereon.core.api.conf.PropertySource
import com.sphereon.core.api.conf.PropertySourceContribution
import com.sphereon.core.api.conf.PropertySourceContributionConfig
import com.sphereon.di.Order
import com.sphereon.openid.fed.common.config.OidfEnvBridgePropertySource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * Registers [OidfEnvBridgePropertySource] into the IDK [com.sphereon.core.api.conf.AppConfigService]
 * property-source set so legacy SCREAMING_CASE env vars (and IDK-normalized OIDF_* via
 * [com.sphereon.openid.fed.common.config.getEnvironmentVariable]) resolve inside the full
 * config pipeline — not only the OIDF [com.sphereon.openid.fed.core.config.OidfPropertyResolution]
 * envLookup tier.
 *
 * Disable with `config.providers.oidf-legacy-env.enabled=false`.
 *
 * @see OidfEnvBridgePropertySource
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<PropertySourceContribution>())
class OidfEnvBridgePropertySourceContribution : PropertySourceContribution {

    override val configLevel: ConfigLevel = ConfigLevel.APP
    override val providerId: String = OidfEnvBridgePropertySource.PROVIDER_ID

    override fun isEnabled(resolver: PropertyResolver): Boolean {
        val enabled = resolver.getProperty(
            PropertySourceContributionConfig.enabledKey(providerId),
            Boolean::class,
            true,
        )
        return enabled != false
    }

    override fun getPropertySource(): PropertySource<*> = OidfEnvBridgePropertySource.Default

    override fun getOrder(): Int = Order.HIGH.orderValue
}

/**
 * Manually attach the env bridge to an [ConfigService] when DI contribution bootstrap
 * is not used (tests, embedders).
 */
fun ConfigService.registerOidfEnvBridge(): ConfigService =
    addPropertySource(OidfEnvBridgePropertySource.Default)
