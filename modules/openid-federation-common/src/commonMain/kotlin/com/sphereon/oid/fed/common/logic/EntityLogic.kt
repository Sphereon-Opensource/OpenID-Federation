package com.sphereon.oid.fed.common.logic

import com.sphereon.oid.fed.openapi.models.EntityStatement

class EntityLogic {

    fun getEntityType(entityStatement: EntityStatement): EntityType {
        if (isFederationListEndpointPresent(entityStatement) == true && isAuthorityHintPresent(entityStatement) == false) {
            return EntityType.TRUST_ANCHOR
        } else if (isFederationListEndpointPresent(entityStatement) == true && isAuthorityHintPresent(entityStatement) == true) {
            return EntityType.INTERMEDIATE
        } else if (isFederationListEndpointPresent(entityStatement) == false && isAuthorityHintPresent(entityStatement) == true) {
            return EntityType.LEAF
        } else {
            return EntityType.UNDEFINED
        }
    }

    private fun isAuthorityHintPresent(entityStatement: EntityStatement): Boolean {
        return entityStatement.authorityHints?.isEmpty() == false
    }

    private fun isFederationListEndpointPresent(entityStatement: EntityStatement): Boolean {
        return entityStatement.metadata?.federationEntity?.federationListEndpoint?.isNotEmpty() == true
    }
}

enum class EntityType {
    LEAF, INTERMEDIATE, TRUST_ANCHOR, UNDEFINED
}
