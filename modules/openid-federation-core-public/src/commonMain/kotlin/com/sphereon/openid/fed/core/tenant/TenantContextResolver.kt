package com.sphereon.openid.fed.core.tenant

import com.sphereon.core.api.http.GenericHttpRequest

/**
 * SPI for resolving federation entity/tenant context from HTTP requests and names.
 *
 * ## Modes
 * Bound by mode-aware impl in account-impl:
 * - **ACCOUNT:** session [SessionExecution.tenantId] (JWT open + optional header rebind) —
 *   not a re-read of `X-Account-Username` at the business layer
 * - **EXTERNAL:** session tenant from JWT only (no identity headers)
 *
 * Do **not** use caller-controlled `X-Tenant-Id` / `X-Principal-Id` as identity.
 *
 * Note: OIDFed **Subordinates** are OpenID Federation trust-hierarchy entities, not
 * IDK sub-tenants or parties.
 */
interface TenantContextResolver {

    /**
     * Resolve tenantId for the current request (prefer DI session after auth ingress).
     */
    suspend fun resolveTenantId(request: GenericHttpRequest): String?

    /**
     * Resolve tenantId from a name (path-based public entity selection).
     * ACCOUNT: account username. EXTERNAL: typically the tenant id itself.
     */
    suspend fun resolveTenantIdByName(name: String): String?

    /**
     * Resolve the federation entity identifier URL for a tenant.
     */
    suspend fun resolveIdentifier(tenantId: String): String?
}
