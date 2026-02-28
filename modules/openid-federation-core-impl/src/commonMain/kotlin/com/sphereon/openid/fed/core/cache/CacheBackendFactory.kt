package com.sphereon.openid.fed.core.cache

import com.sphereon.core.api.cache.CacheBackend

/**
 * Creates a default CacheBackend for the current platform.
 *
 * On JVM and JS: Returns IDK's KacheCacheBackend (from lib-core-api-default/nonWasmMain)
 * On WasmJS: Returns IDK's MapCacheBackend (from lib-core-api-default/wasmJsMain)
 *
 * This factory function bridges OIDF's commonMain code to IDK's platform-specific
 * cache backend implementations, avoiding direct Kache dependency.
 */
internal expect fun createDefaultCacheBackend(): CacheBackend
