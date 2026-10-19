package com.sphereon.openid.fed.services.command.entityConfiguration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.sphereon.openid.fed.common.builder.FederationEntityMetadataObjectBuilder
import com.sphereon.openid.fed.openapi.models.FederationEntityMetadata
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * OpenID Federation 1.1 section 5.1.1: a Trust Anchor or Intermediate MUST publish federation_fetch_endpoint and
 * federation_list_endpoint. Operator-stored federation_entity metadata, such as organization_name, must not remove them.
 */
class FederationEntityMetadataTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private val generated = obj(
        """{"federation_fetch_endpoint":"https://ta.example/fetch","federation_list_endpoint":"https://ta.example/list",""" +
            """"federation_resolve_endpoint":"https://ta.example/resolve"}"""
    )

    @Test
    fun withoutAStoredRowTheGeneratedEndpointsArePublished() {
        assertEquals(generated, federationEntityMetadata(null, generated))
    }

    @Test
    fun storedOrganizationDetailsKeepTheGeneratedEndpoints() {
        val stored = obj("""{"organization_name":"Example Federation","contacts":["ops@example.com"]}""")
        assertEquals(
            obj(
                """{"organization_name":"Example Federation","contacts":["ops@example.com"],""" +
                    """"federation_fetch_endpoint":"https://ta.example/fetch","federation_list_endpoint":"https://ta.example/list",""" +
                    """"federation_resolve_endpoint":"https://ta.example/resolve"}"""
            ),
            federationEntityMetadata(stored, generated),
        )
    }

    @Test
    fun aStoredEndpointIsKeptBecauseTheHostingProductServesIt() {
        val stored = obj("""{"organization_name":"Example","federation_fetch_endpoint":"https://ta.example/federation_fetch"}""")
        val merged = federationEntityMetadata(stored, generated)
        assertEquals("\"https://ta.example/federation_fetch\"", merged["federation_fetch_endpoint"].toString())
        assertEquals("\"https://ta.example/list\"", merged["federation_list_endpoint"].toString())
        assertEquals("\"Example\"", merged["organization_name"].toString())
    }

    /**
     * Regression for an EDK-hosted Trust Anchor: the EDK serves its federation endpoints at federation_fetch-style routes
     * below the Entity Identifier and stores them in the federation_entity row. Every endpoint the Entity Configuration
     * advertises must be one of those served routes, not a path of the standalone Library server.
     */
    @Test
    fun anEdkHostedTrustAnchorAdvertisesOnlyTheRoutesTheEdkServes() {
        val id = "https://fed.example/owner-anchor"
        val served = mapOf(
            "federation_fetch_endpoint" to "$id/federation_fetch",
            "federation_list_endpoint" to "$id/federation_list",
            "federation_resolve_endpoint" to "$id/federation_resolve",
            "federation_trust_mark_status_endpoint" to "$id/federation_trust_mark_status",
            "federation_trust_mark_list_endpoint" to "$id/federation_trust_mark_list",
            "federation_trust_mark_endpoint" to "$id/federation_trust_mark",
            "federation_historical_keys_endpoint" to "$id/federation_historical_keys",
        )
        val stored = JsonObject(served.mapValues { JsonPrimitive(it.value) } + ("organization_name" to JsonPrimitive("Owner Anchor")))
        // What this server generates for a Trust Anchor: every endpoint, at the standalone server's paths.
        val generatedForAnchor = Json.encodeToJsonElement(
            FederationEntityMetadata.serializer(),
            FederationEntityMetadataObjectBuilder().identifier(id).authorityEndpoints(true).trustMarkEndpoints(true)
                .resolveEndpoint(true).historicalKeysEndpoint(true).build(),
        ).jsonObject

        val advertised = federationEntityMetadata(stored, generatedForAnchor)
            .filterKeys { it.startsWith("federation_") && it.endsWith("_endpoint") }
            .mapValues { it.value.jsonPrimitive.content }

        assertEquals(served, advertised)
    }
}
