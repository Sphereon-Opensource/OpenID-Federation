package com.sphereon.openid.fed.httpResolver

import com.sphereon.core.api.cache.CacheSerializer
import com.sphereon.core.api.cache.CacheSerializers

/**
 * Portable [CacheSerializer]s for HTTP resolver caches.
 *
 * ## Boundary
 * Use these (or [CacheSerializers.json]) for any namespace with
 * `distributedFallback` / distributed locality. Do **not** use
 * [com.sphereon.openid.fed.core.cache.IdentityCacheSerializer] across processes.
 */
object HttpMetadataCacheSerializers {
    /** `HttpMetadata<String>` for the federation HTTP resolver cache. */
    val stringValue: CacheSerializer<HttpMetadata<String>> =
        CacheSerializers.json()
}
