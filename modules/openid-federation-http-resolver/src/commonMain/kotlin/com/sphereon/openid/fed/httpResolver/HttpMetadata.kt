package com.sphereon.openid.fed.httpResolver

import kotlinx.serialization.Serializable

/**
 * Cached HTTP response payload with optional conditional-request headers.
 *
 * Marked [Serializable] so IDK distributed cache backends can store entries via
 * [com.sphereon.core.api.cache.CacheSerializers.json] (not [IdentityCacheSerializer]).
 */
@Serializable
data class HttpMetadata<V>(
    val value: V,
    val etag: String? = null,
    val lastModified: String? = null,
)
