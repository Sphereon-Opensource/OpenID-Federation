package com.sphereon.openid.fed.core.tenant

import com.sphereon.core.api.http.GenericHttpRequest

/**
 * SPI for resolving tenant identity from HTTP requests.
 *
 * Implementations:
 * - AccountBasedTenantContextResolver: X-Account-Username header -> Account DB lookup
 * - JwtBearerTenantContextResolver: Authorization bearer -> JWT claims
 * - HeaderBasedTenantContextResolver: X-Tenant-Id header -> direct
 */
interface TenantContextResolver {

    /**
     * Resolve tenantId from an HTTP request. Returns null if unresolvable.
     */
    suspend fun resolveTenantId(request: GenericHttpRequest): String?

    /**
     * Resolve tenantId from a name/username (for path-based resolution).
     */
    suspend fun resolveTenantIdByName(name: String): String?

    /**
     * Resolve the entity identifier URL for a tenant.
     */
    suspend fun resolveIdentifier(tenantId: String): String?
}
