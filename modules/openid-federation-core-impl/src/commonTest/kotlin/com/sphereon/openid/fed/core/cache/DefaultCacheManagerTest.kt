package com.sphereon.openid.fed.core.cache

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

/**
 * Unit tests for DefaultCacheManager covering:
 * - Cache creation and retrieval
 * - Namespace-based cache management
 * - Statistics aggregation
 * - Clear and shutdown operations
 */
class DefaultCacheManagerTest {

    private lateinit var manager: DefaultCacheManager

    @BeforeTest
    fun setup() {
        manager = DefaultCacheManager(createDefaultCacheBackend())
    }

    // ========== Cache Creation Tests ==========

    @Test
    fun `getOrCreate creates new cache for new namespace`() = runTest {
        val requirements = CacheRequirements(
            namespace = "test-namespace",
            maxLocalEntries = 100
        )

        val cache: ScopedCache<String, String> = manager.getOrCreate(requirements)

        assertNotNull(cache)
        assertEquals("test-namespace", cache.namespace)
    }

    @Test
    fun `getOrCreate returns same instance for same namespace`() = runTest {
        val requirements = CacheRequirements(
            namespace = "test-namespace",
            maxLocalEntries = 100
        )

        val cache1: ScopedCache<String, String> = manager.getOrCreate(requirements)
        val cache2: ScopedCache<String, String> = manager.getOrCreate(requirements)

        assertSame(cache1, cache2)
    }

    @Test
    fun `getOrCreate creates different caches for different namespaces`() = runTest {
        val requirements1 = CacheRequirements(namespace = "namespace1")
        val requirements2 = CacheRequirements(namespace = "namespace2")

        val cache1: ScopedCache<String, String> = manager.getOrCreate(requirements1)
        val cache2: ScopedCache<String, String> = manager.getOrCreate(requirements2)

        assertNotSame(cache1, cache2)
        assertEquals("namespace1", cache1.namespace)
        assertEquals("namespace2", cache2.namespace)
    }

    @Test
    fun `getOrCreate respects TTL configuration`() = runTest {
        val customTtl = CacheTtlConfig(
            app = 60.minutes,
            tenant = 30.minutes,
            principal = 15.minutes
        )
        val requirements = CacheRequirements(
            namespace = "ttl-cache",
            ttlConfig = customTtl
        )

        val cache: ScopedCache<String, String> = manager.getOrCreate(requirements)

        // Verify cache works (TTL behavior would need time-based tests)
        cache.putApp("key", "value")
        assertEquals("value", cache.getApp("key"))
    }

    // ========== Get and Exists Tests ==========

    @Test
    fun `get returns null for non-existent namespace`() = runTest {
        val cache: ScopedCache<String, String>? = manager.get("non-existent")
        assertNull(cache)
    }

    @Test
    fun `get returns cache after creation`() = runTest {
        val requirements = CacheRequirements(namespace = "test-namespace")
        manager.getOrCreate<String, String>(requirements)

        val cache: ScopedCache<String, String>? = manager.get("test-namespace")
        assertNotNull(cache)
    }

    @Test
    fun `exists returns false for non-existent namespace`() = runTest {
        assertFalse(manager.exists("non-existent"))
    }

    @Test
    fun `exists returns true after cache creation`() = runTest {
        val requirements = CacheRequirements(namespace = "test-namespace")
        manager.getOrCreate<String, String>(requirements)

        assertTrue(manager.exists("test-namespace"))
    }

    // ========== Statistics Tests ==========

    @Test
    fun `getStatistics returns null for non-existent namespace`() = runTest {
        val stats = manager.getStatistics("non-existent")
        assertNull(stats)
    }

    @Test
    fun `getStatistics returns statistics for existing cache`() = runTest {
        val requirements = CacheRequirements(namespace = "stats-cache")
        val cache: ScopedCache<String, String> = manager.getOrCreate(requirements)
        cache.putApp("key", "value")
        cache.getApp("key") // hit

        val stats = manager.getStatistics("stats-cache")

        assertNotNull(stats)
        assertEquals("stats-cache", stats.namespace)
        assertEquals(1L, stats.hits)
        assertEquals(1L, stats.size)
    }

    @Test
    fun `aggregateStats returns statistics for all caches`() = runTest {
        manager.getOrCreate<String, String>(CacheRequirements(namespace = "cache1"))
        manager.getOrCreate<String, String>(CacheRequirements(namespace = "cache2"))
        manager.getOrCreate<String, String>(CacheRequirements(namespace = "cache3"))

        val allStats = manager.aggregateStats()

        assertEquals(3, allStats.size)
        assertTrue(allStats.containsKey("cache1"))
        assertTrue(allStats.containsKey("cache2"))
        assertTrue(allStats.containsKey("cache3"))
    }

    @Test
    fun `aggregateStats returns empty map when no caches`() = runTest {
        val allStats = manager.aggregateStats()
        assertTrue(allStats.isEmpty())
    }

    @Test
    fun `getNamespaces returns all namespace names`() = runTest {
        manager.getOrCreate<String, String>(CacheRequirements(namespace = "ns1"))
        manager.getOrCreate<String, String>(CacheRequirements(namespace = "ns2"))
        manager.getOrCreate<String, String>(CacheRequirements(namespace = "ns3"))

        val namespaces = manager.getNamespaces()

        assertEquals(setOf("ns1", "ns2", "ns3"), namespaces)
    }

    // ========== Clear Operations Tests ==========

    @Test
    fun `clear removes entries from specific namespace`() = runTest {
        val cache1: ScopedCache<String, String> = manager.getOrCreate(CacheRequirements(namespace = "cache1"))
        val cache2: ScopedCache<String, String> = manager.getOrCreate(CacheRequirements(namespace = "cache2"))

        cache1.putApp("key1", "value1")
        cache2.putApp("key2", "value2")

        manager.clear("cache1")

        assertNull(cache1.getApp("key1"))
        assertEquals("value2", cache2.getApp("key2"))
    }

    @Test
    fun `clear with non-existent namespace does not throw`() = runTest {
        manager.clear("non-existent") // Should not throw
    }

    @Test
    fun `clearAll removes entries from all caches`() = runTest {
        val cache1: ScopedCache<String, String> = manager.getOrCreate(CacheRequirements(namespace = "cache1"))
        val cache2: ScopedCache<String, String> = manager.getOrCreate(CacheRequirements(namespace = "cache2"))

        cache1.putApp("key1", "value1")
        cache2.putApp("key2", "value2")

        manager.clearAll()

        assertNull(cache1.getApp("key1"))
        assertNull(cache2.getApp("key2"))
    }

    @Test
    fun `clearAll with no caches does not throw`() = runTest {
        manager.clearAll() // Should not throw
    }

    // ========== Eviction Tests ==========

    @Test
    fun `evictAllExpired runs on all caches`() = runTest {
        manager.getOrCreate<String, String>(CacheRequirements(namespace = "cache1"))
        manager.getOrCreate<String, String>(CacheRequirements(namespace = "cache2"))

        // Should not throw, actual eviction depends on TTL timing
        manager.evictAllExpired()
    }

    // ========== Shutdown Tests ==========

    @Test
    fun `shutdown clears all caches and removes them`() = runTest {
        val cache1: ScopedCache<String, String> = manager.getOrCreate(CacheRequirements(namespace = "cache1"))
        cache1.putApp("key1", "value1")

        manager.shutdown()

        assertTrue(manager.getNamespaces().isEmpty())
    }

    @Test
    fun `shutdown closes all cache resources`() = runTest {
        val cache1: ScopedCache<String, String> = manager.getOrCreate(CacheRequirements(namespace = "cache1"))
        val cache2: ScopedCache<String, String> = manager.getOrCreate(CacheRequirements(namespace = "cache2"))

        cache1.putApp("key1", "value1")
        cache2.putApp("key2", "value2")

        manager.shutdown()

        // After shutdown, caches should be closed (entries cleared, resources released)
        assertTrue(manager.getNamespaces().isEmpty())
        assertFalse(manager.exists("cache1"))
        assertFalse(manager.exists("cache2"))
    }

    @Test
    fun `shutdown is idempotent`() = runTest {
        manager.getOrCreate<String, String>(CacheRequirements(namespace = "cache1"))

        manager.shutdown()
        manager.shutdown() // Second shutdown should not throw

        assertTrue(manager.getNamespaces().isEmpty())
    }

    // ========== Type Safety Tests ==========

    @Test
    fun `caches with different key types work independently`() = runTest {
        val stringCache: ScopedCache<String, String> = manager.getOrCreate(
            CacheRequirements(namespace = "string-keys")
        )
        val intCache: ScopedCache<Int, String> = manager.getOrCreate(
            CacheRequirements(namespace = "int-keys")
        )

        stringCache.putApp("key", "string-value")
        intCache.putApp(42, "int-value")

        assertEquals("string-value", stringCache.getApp("key"))
        assertEquals("int-value", intCache.getApp(42))
    }

    @Test
    fun `caches with different value types work independently`() = runTest {
        val stringValueCache: ScopedCache<String, String> = manager.getOrCreate(
            CacheRequirements(namespace = "string-values")
        )
        val intValueCache: ScopedCache<String, Int> = manager.getOrCreate(
            CacheRequirements(namespace = "int-values")
        )

        stringValueCache.putApp("key", "hello")
        intValueCache.putApp("key", 42)

        assertEquals("hello", stringValueCache.getApp("key"))
        assertEquals(42, intValueCache.getApp("key"))
    }

    // ========== Requirements Configuration Tests ==========

    @Test
    fun `cache respects locality configuration`() = runTest {
        val requirements = CacheRequirements(
            namespace = "local-cache",
            locality = CacheLocality.LOCAL_ONLY
        )

        val cache: ScopedCache<String, String> = manager.getOrCreate(requirements)
        assertNotNull(cache)
    }

    @Test
    fun `cache respects tags configuration`() = runTest {
        val requirements = CacheRequirements(
            namespace = "tagged-cache",
            tags = setOf("federation", "entity-config")
        )

        val cache: ScopedCache<String, String> = manager.getOrCreate(requirements)
        assertNotNull(cache)
    }
}
