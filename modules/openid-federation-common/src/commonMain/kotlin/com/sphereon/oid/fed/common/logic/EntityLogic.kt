package com.sphereon.oid.fed.common.logic

import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement

class EntityLogic {

    fun getEntityType(entityStatement: EntityConfigurationStatement): EntityType = when {
        isFederationListEndpointPresent(entityStatement) && !isAuthorityHintPresent(entityStatement) -> EntityType.TRUST_ANCHOR
        isFederationListEndpointPresent(entityStatement) && isAuthorityHintPresent(entityStatement) -> EntityType.INTERMEDIATE
        !isFederationListEndpointPresent(entityStatement) && isAuthorityHintPresent(entityStatement) -> EntityType.LEAF
        else -> EntityType.UNDEFINED
    }

    private fun isAuthorityHintPresent(entityStatement: EntityConfigurationStatement): Boolean =
        entityStatement.authorityHints?.isNotEmpty() ?: false

    private fun isFederationListEndpointPresent(entityStatement: EntityConfigurationStatement): Boolean =
        entityStatement.metadata?.federationEntity?.federationListEndpoint?.isNotEmpty() ?: false
}

enum class EntityType {
    LEAF, INTERMEDIATE, TRUST_ANCHOR, UNDEFINED
}
