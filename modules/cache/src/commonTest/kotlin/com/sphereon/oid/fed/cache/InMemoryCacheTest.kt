package com.sphereon.oid.fed.cache

class InMemoryCacheTest : CacheTest() {
    override fun createCache(): Cache<String, TestData> {
        return InMemoryCache(
            maxSize = 1000,
            scope = testScope
        )
    }

    override fun cleanup() {
    }
}