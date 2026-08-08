package com.sphereon.openid.fed.services.mappers

import com.sphereon.openid.fed.openapi.models.Constraints
import com.sphereon.openid.fed.openapi.models.SubordinateConstraints
import com.sphereon.openid.fed.persistence.models.SubordinateConstraint as SubordinateConstraintEntity
import kotlinx.serialization.json.Json

fun SubordinateConstraintEntity.toDTO(json: Json): SubordinateConstraints {
    val constraintsObj = json.decodeFromString<Constraints>(constraints)
    return SubordinateConstraints(
        id = id,
        accountId = account_id,
        subordinateId = subordinate_id,
        constraints = constraintsObj,
        createdAt = created_at?.toString()
    )
}
