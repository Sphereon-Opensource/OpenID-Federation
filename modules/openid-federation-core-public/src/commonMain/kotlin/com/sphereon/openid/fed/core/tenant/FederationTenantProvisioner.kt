package com.sphereon.openid.fed.core.tenant

/**
 * Ensures a federation isolation row exists for a resolved tenant id.
 *
 * In both LEGACY and PLATFORM modes, domain tables still key off `account_id`
 * (federation tenant key). PLATFORM mode uses this to upsert a lightweight row
 * when an IDK session tenant first touches federation data — without exposing
 * `/accounts` management APIs.
 */
interface FederationTenantProvisioner {
    /**
     * Ensure a tenant/entity row exists. Idempotent.
     *
     * @param tenantId Federation tenant key (Account.id / IDK tenant id)
     * @param source Value for tenant_source (see [TenantSource])
     * @param username Optional username (legacy); empty for platform tenants
     * @return true if the tenant exists after the call
     */
    suspend fun ensureTenantExists(
        tenantId: String,
        source: String = TenantSource.IDK,
        username: String = ""
    ): Boolean

    /**
     * Returns true if a non-deleted tenant row exists for [tenantId].
     */
    suspend fun tenantExists(tenantId: String): Boolean
}
