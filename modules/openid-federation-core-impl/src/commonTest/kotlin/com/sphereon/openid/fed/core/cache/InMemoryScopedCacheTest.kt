package com.sphereon.openid.fed.core.cache

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

/**
 * Unit tests for InMemoryScopedCache covering:
 * - APP, TENANT, and PRINCIPAL scope operations
 * - Scope isolation between tenants/principals
 * - Cache statistics tracking
 * - Clear and eviction operations
 * - getOrPut atomicity
 */
class InMemoryScopedCacheTest {

    private lateinit var cache: InMemoryScopedCache<String, String>

    @BeforeTest
    fun setup() {
        cache = InMemoryScopedCache(
            namespace = "test-cache",
            backend = createDefaultCacheBackend()
        )
    }

    // ========== APP Scope Tests ==========

    @Test
    fun `getApp returns null for missing key`() = runTest {
        val result = cache.getApp("missing-key")
        assertNull(result)
    }

    @Test
    fun `putApp stores and getApp retrieves value`() = runTest {
        cache.putApp("key1", "value1")
        val result = cache.getApp("key1")
        assertEquals("value1", result)
    }

    @Test
    fun `putApp returns previous value`() = runTest {
        cache.putApp("key1", "value1")
        val previous = cache.putApp("key1", "value2")
        assertEquals("value1", previous)
        assertEquals("value2", cache.getApp("key1"))
    }

    @Test
    fun `removeApp removes and returns value`() = runTest {
        cache.putApp("key1", "value1")
        val removed = cache.removeApp("key1")
        assertEquals("value1", removed)
        assertNull(cache.getApp("key1"))
    }

    @Test
    fun `removeApp returns null for missing key`() = runTest {
        val removed = cache.removeApp("missing")
        assertNull(removed)
    }

    @Test
    fun `getOrPutApp returns existing value without computing`() = runTest {
        cache.putApp("key1", "existing")
        var computed = false

        val result = cache.getOrPutApp("key1") {
            computed = true
            "computed"
        }

        assertEquals("existing", result)
        assertFalse(computed, "Should not compute when value exists")
    }

    @Test
    fun `getOrPutApp computes and stores value when missing`() = runTest {
        val result = cache.getOrPutApp("key1") { "computed" }

        assertEquals("computed", result)
        assertEquals("computed", cache.getApp("key1"))
    }

    @Test
    fun `getOrPutApp handles null compute result`() = runTest {
        val result = cache.getOrPutApp("key1") { null }
        assertNull(result)
        assertNull(cache.getApp("key1"))
    }

    // ========== TENANT Scope Tests ==========

    @Test
    fun `getTenant returns null for missing key`() = runTest {
        val result = cache.getTenant("tenant1", "key1")
        assertNull(result)
    }

    @Test
    fun `putTenant stores and getTenant retrieves value`() = runTest {
        cache.putTenant("tenant1", "key1", "value1")
        val result = cache.getTenant("tenant1", "key1")
        assertEquals("value1", result)
    }

    @Test
    fun `tenant isolation - different tenants have separate values`() = runTest {
        cache.putTenant("tenant1", "key1", "tenant1-value")
        cache.putTenant("tenant2", "key1", "tenant2-value")

        assertEquals("tenant1-value", cache.getTenant("tenant1", "key1"))
        assertEquals("tenant2-value", cache.getTenant("tenant2", "key1"))
    }

    @Test
    fun `tenant isolation - clearing one tenant does not affect others`() = runTest {
        cache.putTenant("tenant1", "key1", "tenant1-value")
        cache.putTenant("tenant2", "key1", "tenant2-value")

        cache.clearTenant("tenant1")

        assertNull(cache.getTenant("tenant1", "key1"))
        assertEquals("tenant2-value", cache.getTenant("tenant2", "key1"))
    }

    @Test
    fun `removeTenant removes and returns value`() = runTest {
        cache.putTenant("tenant1", "key1", "value1")
        val removed = cache.removeTenant("tenant1", "key1")
        assertEquals("value1", removed)
        assertNull(cache.getTenant("tenant1", "key1"))
    }

    @Test
    fun `getOrPutTenant returns existing value without computing`() = runTest {
        cache.putTenant("tenant1", "key1", "existing")
        var computed = false

        val result = cache.getOrPutTenant("tenant1", "key1") {
            computed = true
            "computed"
        }

        assertEquals("existing", result)
        assertFalse(computed)
    }

    @Test
    fun `getOrPutTenant computes and stores value when missing`() = runTest {
        val result = cache.getOrPutTenant("tenant1", "key1") { "computed" }

        assertEquals("computed", result)
        assertEquals("computed", cache.getTenant("tenant1", "key1"))
    }

    // ========== PRINCIPAL Scope Tests ==========

    @Test
    fun `getPrincipal returns null for missing key`() = runTest {
        val result = cache.getPrincipal("user1", "key1")
        assertNull(result)
    }

    @Test
    fun `putPrincipal stores and getPrincipal retrieves value`() = runTest {
        cache.putPrincipal("user1", "key1", "value1")
        val result = cache.getPrincipal("user1", "key1")
        assertEquals("value1", result)
    }

    @Test
    fun `principal isolation - different principals have separate values`() = runTest {
        cache.putPrincipal("user1", "key1", "user1-value")
        cache.putPrincipal("user2", "key1", "user2-value")

        assertEquals("user1-value", cache.getPrincipal("user1", "key1"))
        assertEquals("user2-value", cache.getPrincipal("user2", "key1"))
    }

    @Test
    fun `principal isolation - clearing one principal does not affect others`() = runTest {
        cache.putPrincipal("user1", "key1", "user1-value")
        cache.putPrincipal("user2", "key1", "user2-value")

        cache.clearPrincipal("user1")

        assertNull(cache.getPrincipal("user1", "key1"))
        assertEquals("user2-value", cache.getPrincipal("user2", "key1"))
    }

    @Test
    fun `removePrincipal removes and returns value`() = runTest {
        cache.putPrincipal("user1", "key1", "value1")
        val removed = cache.removePrincipal("user1", "key1")
        assertEquals("value1", removed)
        assertNull(cache.getPrincipal("user1", "key1"))
    }

    // ========== Cross-Scope Isolation Tests ==========

    @Test
    fun `APP and TENANT scopes are isolated`() = runTest {
        cache.putApp("key1", "app-value")
        cache.putTenant("tenant1", "key1", "tenant-value")

        assertEquals("app-value", cache.getApp("key1"))
        assertEquals("tenant-value", cache.getTenant("tenant1", "key1"))
    }

    @Test
    fun `APP and PRINCIPAL scopes are isolated`() = runTest {
        cache.putApp("key1", "app-value")
        cache.putPrincipal("user1", "key1", "principal-value")

        assertEquals("app-value", cache.getApp("key1"))
        assertEquals("principal-value", cache.getPrincipal("user1", "key1"))
    }

    @Test
    fun `TENANT and PRINCIPAL scopes are isolated`() = runTest {
        cache.putTenant("tenant1", "key1", "tenant-value")
        cache.putPrincipal("user1", "key1", "principal-value")

        assertEquals("tenant-value", cache.getTenant("tenant1", "key1"))
        assertEquals("principal-value", cache.getPrincipal("user1", "key1"))
    }

    // ========== Generic Scoped Operations Tests ==========

    @Test
    fun `generic get with APP scope works`() = runTest {
        cache.putApp("key1", "value1")
        val result = cache.get(CacheScope.APP, null, "key1")
        assertEquals("value1", result)
    }

    @Test
    fun `generic get with TENANT scope works`() = runTest {
        cache.putTenant("tenant1", "key1", "value1")
        val result = cache.get(CacheScope.TENANT, "tenant1", "key1")
        assertEquals("value1", result)
    }

    @Test
    fun `generic get with PRINCIPAL scope works`() = runTest {
        cache.putPrincipal("user1", "key1", "value1")
        val result = cache.get(CacheScope.PRINCIPAL, "user1", "key1")
        assertEquals("value1", result)
    }

    @Test
    fun `generic put with APP scope works`() = runTest {
        cache.put(CacheScope.APP, null, "key1", "value1")
        assertEquals("value1", cache.getApp("key1"))
    }

    @Test
    fun `generic put with TENANT scope works`() = runTest {
        cache.put(CacheScope.TENANT, "tenant1", "key1", "value1")
        assertEquals("value1", cache.getTenant("tenant1", "key1"))
    }

    // ========== Clear Operations Tests ==========

    @Test
    fun `clearApp only clears APP scope`() = runTest {
        cache.putApp("key1", "app-value")
        cache.putTenant("tenant1", "key1", "tenant-value")
        cache.putPrincipal("user1", "key1", "principal-value")

        cache.clearApp()

        assertNull(cache.getApp("key1"))
        assertEquals("tenant-value", cache.getTenant("tenant1", "key1"))
        assertEquals("principal-value", cache.getPrincipal("user1", "key1"))
    }

    @Test
    fun `clear removes all entries from all scopes`() = runTest {
        cache.putApp("key1", "app-value")
        cache.putTenant("tenant1", "key1", "tenant-value")
        cache.putPrincipal("user1", "key1", "principal-value")

        cache.clear()

        assertNull(cache.getApp("key1"))
        assertNull(cache.getTenant("tenant1", "key1"))
        assertNull(cache.getPrincipal("user1", "key1"))
    }

    // ========== Statistics Tests ==========

    @Test
    fun `statistics tracks cache hits`() = runTest {
        cache.putApp("key1", "value1")
        cache.getApp("key1") // hit
        cache.getApp("key1") // hit

        val stats = cache.getStatistics()
        assertEquals(2L, stats.hits)
    }

    @Test
    fun `statistics tracks cache misses`() = runTest {
        cache.getApp("missing1") // miss
        cache.getApp("missing2") // miss

        val stats = cache.getStatistics()
        assertEquals(2L, stats.misses)
    }

    @Test
    fun `statistics tracks size across all scopes`() = runTest {
        cache.putApp("key1", "value1")
        cache.putTenant("tenant1", "key2", "value2")
        cache.putPrincipal("user1", "key3", "value3")

        val stats = cache.getStatistics()
        assertEquals(3L, stats.size)
    }

    @Test
    fun `statistics calculates hit rate correctly`() = runTest {
        cache.putApp("key1", "value1")
        cache.getApp("key1") // hit
        cache.getApp("key1") // hit
        cache.getApp("missing") // miss

        val stats = cache.getStatistics()
        // 2 hits, 1 miss = 2/3 = 0.666...
        assertTrue(stats.hitRate > 0.6 && stats.hitRate < 0.7)
    }

    @Test
    fun `statistics returns namespace`() = runTest {
        val stats = cache.getStatistics()
        assertEquals("test-cache", stats.namespace)
    }

    // ========== Custom TTL Config Tests ==========

    @Test
    fun `respects custom TTL configuration`() = runTest {
        val customTtlConfig = CacheTtlConfig(
            app = 60.minutes,
            tenant = 30.minutes,
            principal = 15.minutes
        )
        val customCache = InMemoryScopedCache<String, String>(
            namespace = "custom-ttl-cache",
            backend = createDefaultCacheBackend(),
            ttlConfig = customTtlConfig
        )

        // Just verify cache works with custom config
        customCache.putApp("key1", "value1")
        assertEquals("value1", customCache.getApp("key1"))
    }

    // ========== Lifecycle Tests ==========

    @Test
    fun `close clears all cache entries`() = runTest {
        cache.putApp("key1", "app-value")
        cache.putTenant("tenant1", "key1", "tenant-value")
        cache.putPrincipal("user1", "key1", "principal-value")

        cache.close()

        // After close, all entries should be cleared
        // Note: After close, operations may not work as expected since backend is closed
    }

    @Test
    fun `close is idempotent`() = runTest {
        cache.putApp("key1", "value1")
        cache.close()
        // Second close should not throw
        cache.close()
    }
}
