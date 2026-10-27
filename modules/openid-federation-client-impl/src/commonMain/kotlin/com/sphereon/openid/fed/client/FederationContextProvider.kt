package com.sphereon.openid.fed.client

import com.sphereon.core.api.cache.CacheManager
import com.sphereon.core.api.conf.TenantConfigService
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.ktor.http.client.provider.HttpClientFactory
import com.sphereon.ktor.http.client.provider.HttpClientOptions
import com.sphereon.ktor.http.client.provider.ResolvedPrivateIpv4Target
import com.sphereon.ktor.http.client.provider.UrlValidationPolicy
import com.sphereon.openid.fed.client.cache.FederationCacheRequirements
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.helpers.OfflineTrustChainPolicy
import com.sphereon.openid.fed.core.cache.OidfCache
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.config.OidfConfigKeys
import com.sphereon.openid.fed.httpResolver.HttpMetadataCacheSerializers
import com.sphereon.openid.fed.httpResolver.HttpResolver
import com.sphereon.openid.fed.httpResolver.config.DefaultHttpResolverConfig
import com.sphereon.openid.fed.httpResolver.config.HttpResolverDefaults
import com.sphereon.openid.fed.httpResolver.readBoundedFederationResponse
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import io.ktor.http.Url

/**
 * Provides FederationContext for dependency injection.
 *
 * This component contributes bindings that make FederationContext available
 * in the session scope, wiring up all required dependencies from IDK.
 */
@ContributesTo(SessionScope::class)
interface FederationContextComponent {

    /**
     * Provides a FederationContext instance configured with all required dependencies.
     *
     * @param jwtService IDK's JwtService for JWT verification
     * @param httpClientFactory Factory for creating HTTP clients
     * @param cacheManager IDK cache manager for creating scoped caches
     * @return Configured FederationContext instance
     */
    @Provides
    @SingleIn(SessionScope::class)
    fun provideFederationContext(
        jwtService: JwtService,
        httpClientFactory: HttpClientFactory,
        cacheManager: CacheManager,
        configBinder: OidfConfigBinder,
        counterpartyEgressSettings: FederationCounterpartyEgressSettings,
        execution: SessionExecution,
        tenantConfigService: TenantConfigService,
    ): FederationContext {
        // Federation URLs come from Entity Identifiers and statements other parties sign: never reach internal addresses.
        val egressPolicy = counterpartyEgressSettings.urlValidationPolicy
        val httpClient = httpClientFactory.createClient(federationCounterpartyClientOptions(egressPolicy))
        val tenantId = execution.tenantId.takeUnless {
            it.isBlank() || it.equals("anonymous", ignoreCase = true)
        }

        val httpReqs = applyLocalityOverride(
            FederationCacheRequirements.HTTP_RESOLVER,
            configBinder,
            OidfConfigKeys.Cache.HTTP_RESOLVER_LOCALITY,
            tenantId,
            tenantConfigService,
        )
        val trustReqs = applyLocalityOverride(
            FederationCacheRequirements.TRUST_CHAIN,
            configBinder,
            OidfConfigKeys.Cache.TRUST_CHAIN_LOCALITY,
            tenantId,
            tenantConfigService,
        )

        // Portable serializers: safe for local + distributed IDK backends
        val httpCache = OidfCache.createStringKeyCache(
            manager = cacheManager,
            requirements = httpReqs,
            valueSerializer = HttpMetadataCacheSerializers.stringValue,
        )
        val trustChainCache = OidfCache.createStringKeyCache(
            manager = cacheManager,
            requirements = trustReqs,
            valueSerializer = com.sphereon.core.api.cache.CacheSerializers.string,
        )

        val httpResolver = HttpResolver(
            config = DefaultHttpResolverConfig(maxStatementReuseSeconds = statementMaxReuseSeconds(configBinder)),
            httpClient = httpClient,
            cache = httpCache,
            responseMapper = ::readBoundedFederationResponse,
        ).also { resolver ->
            // Namespace APP and TENANT cache entries by the process policy, and validate URL syntax before cache reads.
            // A restarted process with a revoked allowlist cannot reuse the previous policy's persistent entries.
            resolver.installRequestPolicy(counterpartyEgressSettings.cacheNamespace) { url ->
                egressPolicy.validate(Url(url))
            }
        }

        return FederationContext(
            jwtService = jwtService,
            httpResolver = httpResolver,
            cacheManager = cacheManager,
            trustChainCache = trustChainCache,
            offlineTrustChainPolicy = offlineTrustChainPolicyFromConfig(configBinder),
        )
    }
}

/** [OidfConfigKeys.Cache.HTTP_RESOLVER_STATEMENT_MAX_REUSE_SECONDS]: empty keeps the default; anything else must be a positive number of seconds. */
internal fun statementMaxReuseSeconds(configBinder: OidfConfigBinder): Long {
    val raw = configBinder.getProperty(OidfConfigKeys.Cache.HTTP_RESOLVER_STATEMENT_MAX_REUSE_SECONDS, "").trim()
    if (raw.isEmpty()) return HttpResolverDefaults.DEFAULT_MAX_STATEMENT_REUSE_SECONDS
    return raw.toLongOrNull()?.takeIf { it > 0 }
        ?: throw IllegalArgumentException("${OidfConfigKeys.Cache.HTTP_RESOLVER_STATEMENT_MAX_REUSE_SECONDS} must be a positive number of seconds")
}

/** APP-level policy snapshot shared by every session; changing the operator allowlist requires a process restart. */
@Inject
@SingleIn(AppScope::class)
class FederationCounterpartyEgressSettings(configBinder: OidfConfigBinder) {
    val urlValidationPolicy: UrlValidationPolicy = federationCounterpartyUrlValidationPolicy(
        configBinder.getProperty(OidfConfigKeys.Client.CounterpartyEgress.PRIVATE_IPV4_TARGETS, ""),
    )
    val cacheNamespace: String = federationCounterpartyCacheNamespace(urlValidationPolicy)
}

internal fun federationCounterpartyClientOptions(privateIpv4Targets: String = ""): HttpClientOptions =
    federationCounterpartyClientOptions(federationCounterpartyUrlValidationPolicy(privateIpv4Targets))

private fun federationCounterpartyClientOptions(egressPolicy: UrlValidationPolicy): HttpClientOptions =
    HttpClientOptions.createDefault().copy(
        urlValidation = egressPolicy,
        followRedirects = false,
    )

internal fun federationCounterpartyUrlValidationPolicy(privateIpv4Targets: String = ""): UrlValidationPolicy {
    val targets = parseFederationPrivateIpv4Targets(privateIpv4Targets)
    return if (targets.isEmpty()) {
        UrlValidationPolicy.COUNTERPARTY_EGRESS
    } else {
        UrlValidationPolicy.COUNTERPARTY_EGRESS.copy(resolvedPrivateIpv4Targets = targets)
    }
}

/** Stable, unambiguous cache partition for a federation egress policy; no hash-code collision can alias policies. */
internal fun federationCounterpartyCacheNamespace(policy: UrlValidationPolicy): String = buildString {
    append("oidf-counterparty-egress-cache-v1")
    append("|userInfoBlocked=").append(policy.blockUserInfo)
    append("|privateBlocked=").append(policy.blockPrivateNetworks)
    append("|rfc1918Blocked=").append(policy.blockRfc1918)
    append("|sharedBlocked=").append(policy.blockSharedNetworks)
    append("|resolvedRequired=").append(policy.requireResolvedAddressEnforcement)
    append("|exactTarget=").append(policy.exactTargetUri?.let(::cacheNamespaceAtom) ?: "-")
    appendCacheNamespaceList("schemes", policy.allowedSchemes.sorted())
    appendCacheNamespaceList("blockedHosts", policy.blockedHosts.sorted())
    appendCacheNamespaceList("blockedSuffixes", policy.blockedHostSuffixes.sorted())
    append("|privateTargets=")
    policy.resolvedPrivateIpv4Targets.sortedWith(compareBy({ it.host }, { it.port })).forEach { target ->
        append(cacheNamespaceAtom(target.host))
        append(target.port).append('=')
        appendCacheNamespaceList("addresses", target.addresses.sorted())
        append(';')
    }
}

private fun StringBuilder.appendCacheNamespaceList(
    name: String,
    values: List<String>,
) {
    append('|').append(name).append('=')
    values.forEach { append(cacheNamespaceAtom(it)) }
}

private fun cacheNamespaceAtom(value: String): String = "${value.length}:$value"

/** Strict APP-level format: `hostname:port=ipv4|ipv4;hostname:port=ipv4`. */
internal fun parseFederationPrivateIpv4Targets(raw: String): Set<ResolvedPrivateIpv4Target> {
    if (raw.isBlank()) return emptySet()
    require(raw.length <= MAX_PRIVATE_TARGET_CONFIG_LENGTH) {
        "${OidfConfigKeys.Client.CounterpartyEgress.PRIVATE_IPV4_TARGETS} must not exceed $MAX_PRIVATE_TARGET_CONFIG_LENGTH characters"
    }
    val entries = raw.split(';')
    require(entries.size <= MAX_CONFIGURED_PRIVATE_TARGETS && entries.none(String::isBlank)) {
        "${OidfConfigKeys.Client.CounterpartyEgress.PRIVATE_IPV4_TARGETS} must contain at most $MAX_CONFIGURED_PRIVATE_TARGETS non-empty entries"
    }
    val parsed = entries.map { entry ->
        require(entry.none(Char::isWhitespace)) { "private IPv4 target configuration must not contain whitespace" }
        val separator = entry.indexOf('=')
        require(separator > 0 && separator == entry.lastIndexOf('=')) {
            "private IPv4 target entries must use hostname:port=address|address"
        }
        val authority = entry.substring(0, separator)
        val colon = authority.lastIndexOf(':')
        require(colon > 0 && colon == authority.indexOf(':')) {
            "private IPv4 target authority must be a DNS hostname and explicit port"
        }
        val host = authority.substring(0, colon)
        val portText = authority.substring(colon + 1)
        val port = portText.toIntOrNull()
        require(port != null && port in 1..65535 && port.toString() == portText) {
            "private IPv4 target port must be a canonical decimal value from 1 to 65535"
        }
        val addresses = entry.substring(separator + 1).split('|')
        require(addresses.none(String::isBlank)) { "private IPv4 target address list must not be empty" }
        require(addresses.size <= MAX_PRIVATE_IPV4_ADDRESSES_PER_HOST) {
            "private IPv4 target address list must not exceed $MAX_PRIVATE_IPV4_ADDRESSES_PER_HOST addresses"
        }
        ResolvedPrivateIpv4Target(host = host, port = port, addresses = addresses.toSet()).also {
            require(it.addresses.size == addresses.size) { "private IPv4 target address list contains duplicates" }
        }
    }
    require(parsed.map { it.host }.distinct().size == parsed.size) {
        "private IPv4 target configuration must contain at most one authority per hostname"
    }
    return parsed.toSet()
}

private const val MAX_CONFIGURED_PRIVATE_TARGETS = 16
private const val MAX_PRIVATE_IPV4_ADDRESSES_PER_HOST = 16
private const val MAX_PRIVATE_TARGET_CONFIG_LENGTH = 8192

/**
 * Build [OfflineTrustChainPolicy] from `oidf.client.offline.trust.chain.*` properties.
 * Empty max-age / min-remaining → [OfflineTrustChainPolicy.DISABLED] (no extra bounds).
 */
internal fun offlineTrustChainPolicyFromConfig(configBinder: OidfConfigBinder): OfflineTrustChainPolicy {
    val maxAge = configBinder.getProperty(OidfConfigKeys.Client.OfflineTrustChain.MAX_AGE_SECONDS, "")
        .trim()
        .takeIf { it.isNotEmpty() }
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }
    val minRemaining = configBinder.getProperty(OidfConfigKeys.Client.OfflineTrustChain.MIN_REMAINING_SECONDS, "")
        .trim()
        .takeIf { it.isNotEmpty() }
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }
    val skew = configBinder.getProperty(OidfConfigKeys.Client.OfflineTrustChain.CLOCK_SKEW_SECONDS, "5")
        .trim()
        .toLongOrNull()
        ?.coerceAtLeast(0)
        ?: OfflineTrustChainPolicy.DEFAULT_CLOCK_SKEW_SECONDS
    if (maxAge == null && minRemaining == null) {
        return OfflineTrustChainPolicy.DISABLED
    }
    return OfflineTrustChainPolicy(
        maxAgeSeconds = maxAge,
        minRemainingSeconds = minRemaining,
        clockSkewSeconds = skew,
    )
}

private fun applyLocalityOverride(
    base: com.sphereon.core.api.cache.CacheRequirements,
    configBinder: OidfConfigBinder,
    key: String,
    tenantId: String?,
    tenantConfigService: TenantConfigService,
): com.sphereon.core.api.cache.CacheRequirements {
    val raw = configBinder.getEffectiveCacheLocality(
        appKey = key,
        tenantId = tenantId,
        sessionTenantConfig = tenantConfigService,
    )
    if (raw.isEmpty()) return base
    val locality = FederationCacheRequirements.parseLocality(raw) ?: return base
    return FederationCacheRequirements.withLocality(base, locality)
}
