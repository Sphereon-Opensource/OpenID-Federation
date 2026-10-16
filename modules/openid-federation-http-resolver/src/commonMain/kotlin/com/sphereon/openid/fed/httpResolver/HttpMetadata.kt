package com.sphereon.openid.fed.httpResolver

import kotlinx.serialization.Serializable

/**
 * Cached HTTP response payload with optional conditional-request headers.
 *
 * [expiresAtEpochSeconds] bounds how long a typed federation JWT may be served from the cache: the earlier of the
 * signed `exp` and any `Cache-Control` freshness lifetime. A typed entry without it is never served.
 *
 * Marked [Serializable] so IDK distributed cache backends can store entries via
 * [com.sphereon.core.api.cache.CacheSerializers.json] (not [IdentityCacheSerializer]).
 */
@Serializable
data class HttpMetadata<V>(
    val value: V,
    val etag: String? = null,
    val lastModified: String? = null,
    val jwtContentType: String? = null,
    val expiresAtEpochSeconds: Long? = null,
)
