package com.sphereon.openid.fed.common.builder

import kotlin.test.Test
import kotlin.test.assertEquals

class FederationEndpointUrlsTest {

    @Test
    fun fetch_matchesMetadataBuilder() {
        val id = "https://ta.example/org/"
        val fromHelper = FederationEndpointUrls.fetch(id)
        val fromMetadata = FederationEntityMetadataObjectBuilder()
            .identifier(id)
            .authorityEndpoints(true)
            .trustMarkEndpoints(false)
            .resolveEndpoint(false)
            .historicalKeysEndpoint(false)
            .build()
            .federationFetchEndpoint

        assertEquals(fromHelper, fromMetadata)
        assertEquals("https://ta.example/org/fetch", fromHelper)
    }

    @Test
    fun multiTenantPath_isUnderEntityIdentifier() {
        assertEquals(
            "https://fed.example/alice/fetch",
            FederationEndpointUrls.fetch("https://fed.example/alice"),
        )
    }
}
