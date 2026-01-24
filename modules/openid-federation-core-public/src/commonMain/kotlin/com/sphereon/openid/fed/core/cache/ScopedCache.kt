package com.sphereon.openid.fed.core.cache

/**
 * A scoped cache that supports multi-tenancy with APP, TENANT, and PRINCIPAL isolation.
 *
 * This interface follows IDK's ScopedCache pattern, providing separate storage
 * for each scope level. Data cached at one scope level is isolated from other scopes.
 *
 * @param K The type of cache keys
 * @param V The type of cached values
 */
interface ScopedCache<K : Any, V : Any> {
    /**
     * The namespace this cache operates under.
     */
    val namespace: String

    // ========== APP-scoped operations ==========

    /**
     * Get a value from the APP scope.
     * APP-scoped data is shared across all tenants.
     */
    suspend fun getApp(key: K): V?

    /**
     * Put a value into the APP scope.
     */
    suspend fun putApp(key: K, value: V): V?

    /**
     * Remove a value from the APP scope.
     */
    suspend fun removeApp(key: K): V?

    /**
     * Get or compute a value in the APP scope.
     */
    suspend fun getOrPutApp(key: K, compute: suspend () -> V?): V?

    // ========== TENANT-scoped operations ==========

    /**
     * Get a value from the TENANT scope.
     * TENANT-scoped data is isolated per tenant/account.
     *
     * @param tenantId The tenant identifier
     */
    suspend fun getTenant(tenantId: String, key: K): V?

    /**
     * Put a value into the TENANT scope.
     *
     * @param tenantId The tenant identifier
     */
    suspend fun putTenant(tenantId: String, key: K, value: V): V?

    /**
     * Remove a value from the TENANT scope.
     *
     * @param tenantId The tenant identifier
     */
    suspend fun removeTenant(tenantId: String, key: K): V?

    /**
     * Get or compute a value in the TENANT scope.
     *
     * @param tenantId The tenant identifier
     */
    suspend fun getOrPutTenant(tenantId: String, key: K, compute: suspend () -> V?): V?

    // ========== PRINCIPAL-scoped operations ==========

    /**
     * Get a value from the PRINCIPAL scope.
     * PRINCIPAL-scoped data is isolated per user principal.
     *
     * @param principalId The principal identifier
     */
    suspend fun getPrincipal(principalId: String, key: K): V?

    /**
     * Put a value into the PRINCIPAL scope.
     *
     * @param principalId The principal identifier
     */
    suspend fun putPrincipal(principalId: String, key: K, value: V): V?

    /**
     * Remove a value from the PRINCIPAL scope.
     *
     * @param principalId The principal identifier
     */
    suspend fun removePrincipal(principalId: String, key: K): V?

    /**
     * Get or compute a value in the PRINCIPAL scope.
     *
     * @param principalId The principal identifier
     */
    suspend fun getOrPutPrincipal(principalId: String, key: K, compute: suspend () -> V?): V?

    // ========== Generic scoped operations ==========

    /**
     * Get a value using the specified scope.
     *
     * @param scope The cache scope
     * @param scopeId The scope identifier (ignored for APP scope)
     */
    suspend fun get(scope: CacheScope, scopeId: String?, key: K): V? = when (scope) {
        CacheScope.APP -> getApp(key)
        CacheScope.TENANT -> getTenant(scopeId!!, key)
        CacheScope.PRINCIPAL -> getPrincipal(scopeId!!, key)
    }

    /**
     * Put a value using the specified scope.
     *
     * @param scope The cache scope
     * @param scopeId The scope identifier (ignored for APP scope)
     */
    suspend fun put(scope: CacheScope, scopeId: String?, key: K, value: V): V? = when (scope) {
        CacheScope.APP -> putApp(key, value)
        CacheScope.TENANT -> putTenant(scopeId!!, key, value)
        CacheScope.PRINCIPAL -> putPrincipal(scopeId!!, key, value)
    }

    // ========== Maintenance operations ==========

    /**
     * Clear all entries from all scopes.
     */
    suspend fun clear()

    /**
     * Clear all entries from the APP scope.
     */
    suspend fun clearApp()

    /**
     * Clear all entries for a specific tenant.
     */
    suspend fun clearTenant(tenantId: String)

    /**
     * Clear all entries for a specific principal.
     */
    suspend fun clearPrincipal(principalId: String)

    /**
     * Evict expired entries from all scopes.
     */
    suspend fun evictExpired()

    /**
     * Get statistics for this cache.
     */
    suspend fun getStatistics(): CacheStatistics

    /**
     * Close the cache and release all resources.
     *
     * After calling close, the cache should not be used anymore.
     * This method cancels any internal coroutines and clears all entries.
     */
    suspend fun close()
}
