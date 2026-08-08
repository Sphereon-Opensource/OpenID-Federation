package com.sphereon.openid.fed.client.helpers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListEndpointUrlTest {

    @Test
    fun buildListEndpointUrl_noParams() {
        assertEquals(
            "https://ta.example/list",
            buildListEndpointUrl("https://ta.example/list"),
        )
    }

    @Test
    fun buildListEndpointUrl_entityTypeAndIntermediate() {
        val url = buildListEndpointUrl(
            listEndpoint = "https://ta.example/list/",
            entityType = "openid_credential_issuer",
            intermediate = true,
        )
        assertTrue(url.startsWith("https://ta.example/list?"))
        assertTrue(url.contains("entity_type=openid_credential_issuer"))
        assertTrue(url.contains("intermediate=true"))
    }

    @Test
    fun buildListEndpointUrl_appendsToExistingQuery() {
        val url = buildListEndpointUrl(
            listEndpoint = "https://ta.example/list?x=1",
            entityType = "openid_wallet_provider",
        )
        assertTrue(url.contains("x=1"))
        assertTrue(url.contains("entity_type=openid_wallet_provider"))
    }
}
