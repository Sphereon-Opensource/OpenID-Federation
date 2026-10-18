package com.sphereon.openid.fed.services.command.renewal

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.helpers.getEntityConfigurationEndpoint
import com.sphereon.openid.fed.client.helpers.getSubordinateStatementEndpoint
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.common.builder.FederationEndpointUrls
import com.sphereon.core.api.log.Log
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive

/**
 * Invalidates the URLs at which this process's [FederationContext] resolver reads a stored statement: the
 * Entity Configuration's well-known URL, or the Subordinate Statement's fetch URL from its `source_endpoint`
 * (falling back to the issuer's default fetch endpoint), with the same `sub` query the resolver builds.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PublishedStatementCacheInvalidator>())
class FederationContextStatementCacheInvalidator(
    private val context: Lazy<FederationContext>,
) : PublishedStatementCacheInvalidator {
    override suspend fun published(statementJwt: String) {
        try {
            for (url in cachedUrls(statementJwt)) context.value.httpResolver.invalidate(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // A failed invalidation only delays visibility until the cached copy's exp; the publish itself stands.
            logger.warn("Could not invalidate cached copies of a published statement: ${e.message}")
        }
    }

    internal companion object {
        private val logger = Log.app().withTag("sphereon:oidf:services:statement-cache-invalidator")

        fun cachedUrls(statementJwt: String): List<String> {
            val payload = decodeJWTComponents(statementJwt).payload
            val iss = (payload["iss"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return emptyList()
            val sub = (payload["sub"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return emptyList()
            if (iss == sub) return listOf(getEntityConfigurationEndpoint(iss))
            val declared = (payload["source_endpoint"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() }
            val fallback = FederationEndpointUrls.fetch(iss)
            return listOfNotNull(declared, fallback).distinct().map { getSubordinateStatementEndpoint(it, sub) }
        }
    }
}
