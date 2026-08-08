package com.sphereon.openid.fed.server.federation.api.http.auth

import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.openid.fed.core.config.FederationEndpointKind
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.server.federation.api.http.FederationErrorResponses

/**
 * Resolve host tenant id + Entity Identifier for §8.8 audience and membership checks.
 */
suspend fun resolveEndpointHost(
    tenantContextResolver: TenantContextResolver,
    username: String,
): Pair<String, String>? {
    val tenantId = tenantContextResolver.resolveTenantIdByName(username) ?: return null
    val entityId = tenantContextResolver.resolveIdentifier(tenantId) ?: return null
    return tenantId to entityId
}

/**
 * Enforce §8.8 client authentication; returns an error response or null if allowed.
 */
suspend fun enforceFederationClientAuth(
    clientAuth: FederationEndpointClientAuthService,
    tenantContextResolver: TenantContextResolver,
    username: String,
    endpoint: FederationEndpointKind,
    methodIsPost: Boolean,
    params: Map<String, String>,
): GenericHttpResponse? {
    val host = resolveEndpointHost(tenantContextResolver, username)
        ?: return FederationErrorResponses.notFound("Tenant not found")
    val (hostTenantId, audienceEntityId) = host
    return clientAuth.enforce(
        endpoint = endpoint,
        methodIsPost = methodIsPost,
        formOrQueryParams = params,
        hostTenantId = hostTenantId,
        audienceEntityId = audienceEntityId,
    )
}

fun Map<String, String?>.asAuthParams(): Map<String, String> =
    mapValues { it.value.orEmpty() }
