package com.sphereon.openid.fed.core.cache

import com.sphereon.core.api.cache.CacheBackend
import com.sphereon.core.api.cache.MapCacheBackend

internal actual fun createDefaultCacheBackend(): CacheBackend = MapCacheBackend()
