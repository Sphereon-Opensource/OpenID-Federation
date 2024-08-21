package com.sphereon.oid.fed.services.extensions

import com.sphereon.oid.fed.openapi.models.SubordinateAdminDTO
import com.sphereon.oid.fed.persistence.models.Subordinate

fun Subordinate.toSubordinateAdminDTO(): SubordinateAdminDTO {
    return SubordinateAdminDTO(
        id = this.id,
        accountId = this.account_id,
        identifier = this.identifier,
        createdAt = this.created_at.toString(),
    )
}
