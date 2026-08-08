package com.sphereon.openid.fed.server.federation.api.http

import kotlin.test.Test
import kotlin.test.assertEquals

class MultiValueParamsTest {

    @Test
    fun fromMapSplitsCommaJoinedQueryValues() {
        // IDK Ktor adapter joins repeated keys with commas
        val params = mapOf(
            "trust_anchor" to "https://ta-a.example,https://ta-b.example",
            "entity_type" to "openid_credential_issuer",
        )
        assertEquals(
            listOf("https://ta-a.example", "https://ta-b.example"),
            MultiValueParams.fromMap(params, "trust_anchor"),
        )
        assertEquals(
            listOf("openid_credential_issuer"),
            MultiValueParams.fromMap(params, "entity_type"),
        )
    }

    @Test
    fun parseFormMultiPreservesRepeatedKeys() {
        val body =
            "sub=https%3A%2F%2Fleaf.example" +
                "&trust_anchor=https%3A%2F%2Fta-a.example" +
                "&trust_anchor=https%3A%2F%2Fta-b.example" +
                "&entity_type=openid_credential_issuer" +
                "&entity_type=oauth_authorization_server"
        val multi = MultiValueParams.parseFormMulti(body)
        assertEquals("https://leaf.example", MultiValueParams.first(multi, "sub"))
        assertEquals(
            listOf("https://ta-a.example", "https://ta-b.example"),
            MultiValueParams.all(multi, "trust_anchor"),
        )
        assertEquals(
            listOf("openid_credential_issuer", "oauth_authorization_server"),
            MultiValueParams.all(multi, "entity_type"),
        )
    }
}
