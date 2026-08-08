package com.sphereon.openid.fed.server.federation.api.http

/**
 * Helpers for OpenID Federation multi-valued request parameters.
 *
 * Ktor → [com.sphereon.core.api.http.GenericHttpRequest] joins repeated query keys with commas
 * (see IDK `toGenericHttpRequest`). Spec also allows repeated form fields on POST.
 * Entity Identifiers and Entity Type Identifiers do not contain unencoded commas.
 */
object MultiValueParams {

    /**
     * Collect values for [key] from a single-value map (query or last-wins form map).
     * Splits comma-joined values into distinct entries.
     */
    fun fromMap(params: Map<String, String?>, key: String): List<String> {
        val raw = params[key] ?: return emptyList()
        return splitCsv(raw)
    }

    /**
     * Parse `application/x-www-form-urlencoded` body preserving **all** values per key
     * (repeated keys and comma-joined values).
     */
    fun parseFormMulti(body: String): Map<String, List<String>> {
        if (body.isBlank()) return emptyMap()
        val result = linkedMapOf<String, MutableList<String>>()
        for (param in body.split("&")) {
            if (param.isEmpty()) continue
            val parts = param.split("=", limit = 2)
            val key = decode(parts[0])
            val value = if (parts.size > 1) decode(parts[1]) else ""
            val list = result.getOrPut(key) { mutableListOf() }
            // Each form field may itself be comma-joined
            list.addAll(splitCsv(value).ifEmpty { listOf(value) })
        }
        return result.mapValues { (_, v) -> v.filter { it.isNotBlank() } }
    }

    fun first(multi: Map<String, List<String>>, key: String): String? =
        multi[key]?.firstOrNull { it.isNotBlank() }

    fun all(multi: Map<String, List<String>>, key: String): List<String> =
        multi[key].orEmpty().filter { it.isNotBlank() }

    private fun splitCsv(raw: String): List<String> =
        raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun decode(s: String): String =
        try {
            java.net.URLDecoder.decode(s, Charsets.UTF_8)
        } catch (_: Exception) {
            s
        }
}
