package com.sphereon.openid.fed.core.cache

import com.sphereon.core.api.cache.CacheSerializer

/**
 * In-process [CacheSerializer] that stores typed values in a side map.
 *
 * ## Boundary
 * Used when OIDF needs generic `K`/`V` caches (e.g. HttpMetadata) without kotlinx
 * serialization of those types. Suitable for **local** IDK backends only —
 * tokens written to the backend are opaque handles, not portable bytes.
 *
 * ## Determinism
 * The same value (by [Any.equals]) always maps to the same handle so
 * put-then-get works. Concurrent access is best-effort (single-process local cache).
 *
 * For distributed caches, register a real [CacheSerializer] instead.
 */
class IdentityCacheSerializer<T : Any> : CacheSerializer<T> {
    private val byHandle = HashMap<String, T>()
    private val handleByValue = HashMap<T, String>()
    private var seq = 0L

    override fun serialize(value: T): ByteArray {
        val existing = handleByValue[value]
        if (existing != null) {
            return existing.encodeToByteArray()
        }
        val handle = "${++seq}"
        byHandle[handle] = value
        handleByValue[value] = handle
        return handle.encodeToByteArray()
    }

    override fun deserialize(bytes: ByteArray): T {
        val handle = bytes.decodeToString()
        return byHandle[handle]
            ?: error("IdentityCacheSerializer: missing entry for handle $handle")
    }

    override fun serializeToString(value: T): String = serialize(value).decodeToString()

    override fun deserializeFromString(string: String): T = deserialize(string.encodeToByteArray())
}
