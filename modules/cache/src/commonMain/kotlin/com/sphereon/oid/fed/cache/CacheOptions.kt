package com.sphereon.oid.fed.cache

enum class CacheStrategy {
    CACHE_FIRST,
    CACHE_ONLY,
    FORCE_REMOTE
}

data class CacheOptions(
    val strategy: CacheStrategy = CacheStrategy.CACHE_FIRST
)