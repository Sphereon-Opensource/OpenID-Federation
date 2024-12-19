package com.sphereon.oid.fed.services.extensions

import com.sphereon.oid.fed.openapi.models.TrustMarkDefinitionDTO
import com.sphereon.oid.fed.persistence.models.TrustMarkDefinition

fun TrustMarkDefinition.toTrustMarkDefinitionDTO(): TrustMarkDefinitionDTO {
    return TrustMarkDefinitionDTO(
        id = this.id,
        identifier = this.identifier,
        name = this.name,
        description = this.description,
        createdAt = this.created_at.toString(),
        updatedAt = this.updated_at?.toString()
    )
}
