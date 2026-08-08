package com.sphereon.openid.fed.core.cache

import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.core.api.cache.CacheSerializers
import com.sphereon.core.api.cache.CacheTtlConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.minutes

/**
 * Unit tests for [OidfCache] helpers over IDK [com.sphereon.core.api.cache.DefaultCacheManager].
 */
class OidfCacheTest {

    private lateinit var manager: com.sphereon.core.api.cache.CacheManager

    @BeforeTest
    fun setup() {
        manager = OidfCache.newManager()
    }

    @Test
    fun `createTypedCache creates namespace cache`() = runTest {
        val requirements = CacheRequirements(
            namespace = "test-namespace",
            maxLocalEntries = 100,
        )
        val cache = OidfCache.createStringKeyCache(
            manager,
            requirements,
            CacheSerializers.string,
        )
        assertNotNull(cache)
        assertEquals("test-namespace", cache.namespace)
    }

    @Test
    fun `createTypedCache returns same instance for same namespace`() = runTest {
        val requirements = CacheRequirements(namespace = "test-namespace", maxLocalEntries = 100)
        val cache1 = OidfCache.createStringKeyCache(manager, requirements, CacheSerializers.string)
        val cache2 = OidfCache.createStringKeyCache(manager, requirements, CacheSerializers.string)
        assertSame(cache1, cache2)
    }

    @Test
    fun `different namespaces produce different caches`() = runTest {
        val cache1 = OidfCache.createStringKeyCache(
            manager,
            CacheRequirements(namespace = "namespace1"),
            CacheSerializers.string,
        )
        val cache2 = OidfCache.createStringKeyCache(
            manager,
            CacheRequirements(namespace = "namespace2"),
            CacheSerializers.string,
        )
        assertNotSame(cache1, cache2)
        assertEquals("namespace1", cache1.namespace)
        assertEquals("namespace2", cache2.namespace)
    }

    @Test
    fun `putApp and getApp round-trip with string serializers`() = runTest {
        val cache = OidfCache.createStringKeyCache(
            manager,
            CacheRequirements(
                namespace = "ttl-cache",
                ttlConfig = CacheTtlConfig(
                    app = 60.minutes,
                    tenant = 30.minutes,
                    principal = 15.minutes,
                ),
            ),
            CacheSerializers.string,
        )
        cache.putApp("key", "value")
        assertEquals("value", cache.getApp("key"))
    }

    @Test
    fun `string key cache works for string values`() = runTest {
        val cache = OidfCache.createStringKeyCache(
            manager = manager,
            requirements = CacheRequirements(namespace = "string-cache"),
            valueSerializer = CacheSerializers.string,
        )
        cache.putApp("k", "v")
        assertEquals("v", cache.getApp("k"))
    }

    @Test
    fun `tenant scope isolates keys`() = runTest {
        val cache = OidfCache.createStringKeyCache(
            manager,
            CacheRequirements(namespace = "tenant-iso"),
            CacheSerializers.string,
        )
        cache.putTenant("t1", "k", "v1")
        cache.putTenant("t2", "k", "v2")
        assertEquals("v1", cache.getTenant("t1", "k"))
        assertEquals("v2", cache.getTenant("t2", "k"))
    }

    @Test
    fun `getCache returns cache after create`() = runTest {
        OidfCache.createStringKeyCache(
            manager,
            CacheRequirements(namespace = "lookup"),
            CacheSerializers.string,
        )
        val found = manager.getCache<String, String>("lookup")
        assertNotNull(found)
    }

    @Test
    fun `clearAll empties entries`() = runTest {
        val cache = OidfCache.createStringKeyCache(
            manager,
            CacheRequirements(namespace = "clear-me"),
            CacheSerializers.string,
        )
        cache.putApp("a", "1")
        manager.clearAll()
        assertEquals(null, cache.getApp("a"))
    }
}
