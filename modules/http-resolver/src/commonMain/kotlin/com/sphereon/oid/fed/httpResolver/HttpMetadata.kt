package com.sphereon.oid.fed.httpResolver

data class HttpMetadata<V>(
    val value: V,
    val etag: String? = null,
    val lastModified: String? = null
)