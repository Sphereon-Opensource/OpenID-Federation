package com.sphereon.openid.fed.client.helpers

import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.Jwt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Helpers for the Entity Configuration claim `trust_anchor_hints` (OIDFed 1.1 §3.1.2).
 *
 * Leaf and Intermediate Entity Configurations MAY publish the Trust Anchors they trust.
 * Clients use these hints to choose among configured Trust Anchors or as a fallback when
 * the caller does not supply Trust Anchors.
 */
object TrustAnchorHints {

    /**
     * Extract non-empty `trust_anchor_hints` from an Entity Configuration payload, or null.
     */
    fun extract(payload: JsonObject): List<String>? {
        val raw = payload["trust_anchor_hints"] as? JsonArray ?: return null
        val hints = raw.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { s -> s.isNotBlank() } }
        return hints.takeIf { it.isNotEmpty() }
    }

    fun extract(jwt: Jwt): List<String>? = extract(jwt.payload)

    /**
     * Decode a compact Entity Configuration JWT and extract `trust_anchor_hints`.
     */
    fun extractFromCompactJwt(entityConfigurationJwt: String): List<String>? =
        extract(decodeJWTComponents(entityConfigurationJwt))

    fun extract(entityConfiguration: EntityConfigurationStatement): List<String>? {
        val hints = entityConfiguration.trustAnchorHints ?: return null
        return hints.map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
    }

    /**
     * Effective Trust Anchors for chain resolution (OIDFed client policy):
     *
     * - If [configured] is empty and [publishedHints] is non-empty → use published hints.
     * - If both are non-empty → reorder [configured] so anchors also present in hints come first
     *   (caller remains authoritative; hints only affect preference order for multi-chain selection).
     * - Otherwise → [configured] unchanged.
     */
    fun effectiveTrustAnchors(
        configured: Array<String>,
        publishedHints: List<String>?,
    ): Array<String> {
        val hints = publishedHints?.filter { it.isNotBlank() }.orEmpty()
        if (hints.isEmpty()) return configured

        if (configured.isEmpty()) {
            return hints.toTypedArray()
        }

        val hintSet = hints.toSet()
        val preferred = configured.filter { it in hintSet }
        val rest = configured.filter { it !in hintSet }
        return (preferred + rest).toTypedArray()
    }
}
