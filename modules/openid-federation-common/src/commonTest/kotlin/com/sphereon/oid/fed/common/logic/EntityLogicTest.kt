package com.sphereon.oid.fed.common.logic

import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.JWKS
import com.sphereon.oid.fed.openapi.models.Metadata
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class EntityLogicTest {

    private val entityLogic = EntityLogic()

    // ignoreUnknownKeys added because OpenAPI model misses few objects
    // Need to fix OpenAPI model
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun shouldReturnTrustAnchor() {
        val trustAnchorEntityStatement =
            json.decodeFromString<EntityConfigurationStatement>(TRUST_ANCHOR_ENTITY_STATEMENT)

        assertEquals(EntityType.TRUST_ANCHOR, entityLogic.getEntityType(trustAnchorEntityStatement))
    }

    @Test
    fun shouldReturnIntermediate() {
        val intermediateEntityStatement =
            json.decodeFromString<EntityConfigurationStatement>(INTERMEDIATE_ENTITY_STATEMENT)

        assertEquals(EntityType.INTERMEDIATE, entityLogic.getEntityType(intermediateEntityStatement))
    }

    @Test
    fun shouldReturnLeafEntity() {
        val leafEntityStatement = json.decodeFromString<EntityConfigurationStatement>(LEAF_ENTITY_STATEMENT)

        assertEquals(EntityType.LEAF, entityLogic.getEntityType(leafEntityStatement))
    }

    @Test
    fun shouldReturnUndefined() {
        val entityStatement = EntityConfigurationStatement(
            metadata = Metadata(federationEntity = null),
            authorityHints = emptyList(),
            exp = 0,
            iat = 0,
            iss = "",
            sub = "",
            jwks = JWKS(),
        )

        assertEquals(EntityType.UNDEFINED, entityLogic.getEntityType(entityStatement))
    }
}
