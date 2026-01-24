package com.sphereon.openid.fed.httpResolver

data class HttpMetadata<V>(
    val value: V,
    val etag: String? = null,
    val lastModified: String? = null
)