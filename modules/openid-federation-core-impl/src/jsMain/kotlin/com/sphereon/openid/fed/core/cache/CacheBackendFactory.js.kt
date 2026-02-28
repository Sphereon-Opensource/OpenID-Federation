package com.sphereon.openid.fed.core.cache

import com.sphereon.core.api.cache.CacheBackend
import com.sphereon.core.api.cache.KacheCacheBackend

internal actual fun createDefaultCacheBackend(): CacheBackend = KacheCacheBackend()
