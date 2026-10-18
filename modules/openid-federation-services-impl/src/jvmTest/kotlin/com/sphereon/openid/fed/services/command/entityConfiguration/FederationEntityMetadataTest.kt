package com.sphereon.openid.fed.services.command.entityConfiguration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
    fun aStoredEndpointDoesNotReplaceTheEndpointThisServerServes() {
        val stored = obj("""{"organization_name":"Example","federation_fetch_endpoint":"https://old.example/fetch"}""")
        assertEquals("\"https://ta.example/fetch\"", federationEntityMetadata(stored, generated)["federation_fetch_endpoint"].toString())
        assertEquals("\"Example\"", federationEntityMetadata(stored, generated)["organization_name"].toString())
    }
}
