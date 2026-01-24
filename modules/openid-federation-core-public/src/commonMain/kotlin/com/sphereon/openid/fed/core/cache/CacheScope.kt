package com.sphereon.openid.fed.core.cache

/**
 * Defines the scope levels for cached data following IDK's multi-tenancy patterns.
 *
 * Cache scopes provide isolation at different levels:
 * - APP: Shared across all tenants (global data like well-known trust anchors)
 * - TENANT: Isolated per tenant/account (per-account entity configurations)
 * - PRINCIPAL: Isolated per user principal (request-specific caching)
 */
enum class CacheScope {
    /**
     * Application-wide scope.
     * Data cached at this level is shared across all tenants.
     * Use for: Trust anchor configurations, well-known entity configs, shared trust chains.
     */
    APP,

    /**
     * Tenant-isolated scope.
     * Data cached at this level is isolated per tenant/account.
     * Use for: Per-account entity configurations, tenant-specific trust chains.
     */
    TENANT,

    /**
     * Principal-isolated scope.
     * Data cached at this level is isolated per user principal.
     * Use for: Request-specific caching, user session data.
     */
    PRINCIPAL
}
