package com.sphereon.oid.fed.services.mappers

import com.sphereon.oid.fed.openapi.models.Subordinate
import com.sphereon.oid.fed.persistence.models.Subordinate as SubordinateEntity

fun SubordinateEntity.toDTO(): Subordinate {
    return Subordinate(
        id = this.id,
        accountId = this.account_id,
        identifier = this.identifier,
        createdAt = this.created_at.toString(),
    )
}
