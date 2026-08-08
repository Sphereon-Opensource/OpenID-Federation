package com.sphereon.openid.fed.client.helpers

/**
 * Build a federation list endpoint URL with OIDFed 1.1 §8.2 query parameters.
 */
fun buildListEndpointUrl(
    listEndpoint: String,
    entityType: String? = null,
    trustMarked: Boolean? = null,
    trustMarkType: String? = null,
    intermediate: Boolean? = null,
): String {
    val base = listEndpoint.trimEnd('/')
    val params = buildList {
        if (!entityType.isNullOrBlank()) add("entity_type" to entityType)
        if (trustMarked != null) add("trust_marked" to trustMarked.toString())
        if (!trustMarkType.isNullOrBlank()) add("trust_mark_type" to trustMarkType)
        if (intermediate != null) add("intermediate" to intermediate.toString())
    }
    if (params.isEmpty()) return base
    val query = params.joinToString("&") { (k, v) ->
        "$k=${encodeQueryComponent(v)}"
    }
    return if (base.contains('?')) "$base&$query" else "$base?$query"
}

/**
 * Minimal query encoding (KMP-friendly; sufficient for entity types / booleans / URIs).
 */
private fun encodeQueryComponent(value: String): String = buildString {
    for (ch in value) {
        when {
            ch.isLetterOrDigit() || ch in "-._~" -> append(ch)
            ch == ' ' -> append('+')
            else -> {
                val bytes = ch.toString().encodeToByteArray()
                for (b in bytes) {
                    append('%')
                    append(((b.toInt() shr 4) and 0xF).toString(16).uppercase())
                    append((b.toInt() and 0xF).toString(16).uppercase())
                }
            }
        }
    }
}
