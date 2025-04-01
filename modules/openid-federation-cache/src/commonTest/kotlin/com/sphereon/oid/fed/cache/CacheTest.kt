package com.sphereon.oid.fed.cache

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*

abstract class CacheTest {
    data class TestData(val value: String, val number: Int) {
        override fun toString(): String = "$value:$number"
    }

    protected abstract fun createCache(): Cache<String, TestData>
    protected abstract fun cleanup()

    private lateinit var cache: Cache<String, TestData>
    protected val testDispatcher = StandardTestDispatcher()
    protected val testScope = TestScope(testDispatcher)

    @BeforeTest
    fun setup() {
        cache = createCache()
    }

    @AfterTest
    fun teardown() {
        testDispatcher.scheduler.advanceUntilIdle()
        cleanup()
    }

    @Test
    fun testBasicOperations() = testScope.runTest {
        val testData = TestData("test value", 42)
        val key = "test-key"

        cache.put(key, testData)
        testDispatcher.scheduler.advanceUntilIdle()

        val retrieved = cache.get(key)
        assertEquals(testData, retrieved)

        val removed = cache.remove(key)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(testData, removed)
        assertNull(cache.get(key))

        cache.put(key, testData)
        testDispatcher.scheduler.advanceUntilIdle()

        cache.clear()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(cache.get(key))
    }

    @Test
    fun testGetOrPut() = testScope.runTest {
        val testData = TestData("test value", 42)
        val key = "test-key"

        val result = cache.getOrPut(key, creationFunction = { _ -> testData }, options = CacheOptions())
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(testData, result)

        val cachedResult =
            cache.getOrPut(key, creationFunction = { _ -> TestData("different value", 100) }, options = CacheOptions())
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(testData, cachedResult)
    }

    @Test
    fun testCacheStrategies() = testScope.runTest {
        val testData = TestData("test value", 42)
        val key = "test-key"

        cache.put(key, testData)
        testDispatcher.scheduler.advanceUntilIdle()

        val cachedValue = cache.get(key, CacheOptions(strategy = CacheStrategy.CACHE_FIRST))
        assertEquals(testData, cachedValue)

        val cacheOnlyValue = cache.get(key, CacheOptions(strategy = CacheStrategy.CACHE_ONLY))
        assertEquals(testData, cacheOnlyValue)

        val forceRemoteValue = cache.get(key, CacheOptions(strategy = CacheStrategy.FORCE_REMOTE))
        assertNull(forceRemoteValue)
    }

    @Test
    fun testSizeOperations() = testScope.runTest {
        val initialSize = cache.getCurrentSize()
        assertEquals(0, initialSize)

        repeat(5) { i ->
            cache.put("key$i", TestData("value$i", i))
            testDispatcher.scheduler.advanceUntilIdle()
        }

        val newSize = cache.getCurrentSize()
        assertEquals(5, newSize)

        cache.resize(3)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(cache.getCurrentSize() <= 3)
    }

    @Test
    fun testKeyOperations() = testScope.runTest {
        val entries = (1..3).associate { i ->
            "key$i" to TestData("value$i", i)
        }
        cache.putAll(entries)
        testDispatcher.scheduler.advanceUntilIdle()

        val allKeys = cache.getAllKeys()
        assertEquals(entries.keys, allKeys)

        val keys = cache.getKeys()
        assertEquals(entries.keys, keys)
    }
}