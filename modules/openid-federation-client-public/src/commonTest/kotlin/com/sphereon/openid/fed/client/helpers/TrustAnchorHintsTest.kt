package com.sphereon.openid.fed.client.helpers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrustAnchorHintsTest {

    @Test
    fun extractNonEmptyHints() {
        val payload = JsonObject(
            mapOf(
                "trust_anchor_hints" to JsonArray(
                    listOf(
                        JsonPrimitive("https://ta-a.example"),
                        JsonPrimitive("https://ta-b.example"),
                    )
                )
            )
        )
        assertEquals(
            listOf("https://ta-a.example", "https://ta-b.example"),
            TrustAnchorHints.extract(payload)
        )
    }

    @Test
    fun extractEmptyOrMissingReturnsNull() {
        assertNull(TrustAnchorHints.extract(JsonObject(emptyMap())))
        assertNull(
            TrustAnchorHints.extract(
                JsonObject(mapOf("trust_anchor_hints" to JsonArray(emptyList())))
            )
        )
    }

    @Test
    fun effectiveUsesPublishedWhenConfiguredEmpty() {
        val effective = TrustAnchorHints.effectiveTrustAnchors(
            configured = emptyArray(),
            publishedHints = listOf("https://ta.example"),
        )
        assertContentEquals(arrayOf("https://ta.example"), effective)
    }

    @Test
    fun effectivePrefersIntersectionOrder() {
        val effective = TrustAnchorHints.effectiveTrustAnchors(
            configured = arrayOf("https://ta-x.example", "https://ta-a.example", "https://ta-y.example"),
            publishedHints = listOf("https://ta-a.example", "https://ta-b.example"),
        )
        // ta-a is in both → first; remaining configured keep relative order
        assertContentEquals(
            arrayOf("https://ta-a.example", "https://ta-x.example", "https://ta-y.example"),
            effective
        )
    }

    @Test
    fun effectiveUnchangedWhenNoHints() {
        val configured = arrayOf("https://ta.example")
        val effective = TrustAnchorHints.effectiveTrustAnchors(configured, null)
        assertContentEquals(configured, effective)
        assertTrue(effective === configured || effective.contentEquals(configured))
    }
}
