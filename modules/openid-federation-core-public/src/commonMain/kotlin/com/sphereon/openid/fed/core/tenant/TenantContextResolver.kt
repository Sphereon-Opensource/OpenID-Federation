package com.sphereon.openid.fed.core.tenant

import com.sphereon.core.api.http.GenericHttpRequest

/**
 * SPI for resolving federation entity/tenant context from HTTP requests and names.
 *
 * ## Modes
 * Bound by [com.sphereon.openid.fed.core.tenant.ModeAwareTenantContextResolver] (impl):
 * - **LEGACY:** `AccountBasedTenantContextResolver` — `X-Account-Username` → Account.id
 * - **PLATFORM:** `SessionTenantContextResolver` — IDK session tenant (JWT-resolved by host)
 *
 * Do **not** use caller-controlled `X-Tenant-Id` / `X-Principal-Id` headers as identity
 * in PLATFORM mode (IDK AuthHeaders reject those for identity establishment).
 *
 * Note: OIDFed **Subordinates** are OpenID Federation trust-hierarchy entities, not
 * IDK sub-tenants or parties.
 */
interface TenantContextResolver {

    /**
     * Resolve tenantId from an HTTP request. Returns null if unresolvable.
     */
    suspend fun resolveTenantId(request: GenericHttpRequest): String?

    /**
     * Resolve tenantId from a name/username (for path-based resolution).
     * LEGACY: account username. PLATFORM: typically the tenant id itself or a configured alias.
     */
    suspend fun resolveTenantIdByName(name: String): String?

    /**
     * Resolve the federation entity identifier URL for a tenant.
     */
    suspend fun resolveIdentifier(tenantId: String): String?
}
