package com.sphereon.oid.fed.common.logic

import com.sphereon.oid.fed.openapi.models.EntityStatement
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
        val trustAnchorEntityStatement = json.decodeFromString<EntityStatement>(TRUST_ANCHOR_ENTITY_STATEMENT)

        assertEquals(EntityType.TRUST_ANCHOR, entityLogic.getEntityType(trustAnchorEntityStatement))
    }

    @Test
    fun shouldReturnIntermediate() {
        val intermediateEntityStatement = json.decodeFromString<EntityStatement>(INTERMEDIATE_ENTITY_STATEMENT)

        assertEquals(EntityType.INTERMEDIATE, entityLogic.getEntityType(intermediateEntityStatement))
    }

    @Test
    fun shouldReturnLeafEntity() {
        val leafEntityStatement = json.decodeFromString<EntityStatement>(LEAF_ENTITY_STATEMENT)

        assertEquals(EntityType.LEAF, entityLogic.getEntityType(leafEntityStatement))
    }

    @Test
    fun shouldReturnUndefined() {
        val entityStatement = EntityStatement(
            metadata = Metadata(federationEntity = null), authorityHints = emptyList()
        )

        assertEquals(EntityType.UNDEFINED, entityLogic.getEntityType(entityStatement))
    }
}
