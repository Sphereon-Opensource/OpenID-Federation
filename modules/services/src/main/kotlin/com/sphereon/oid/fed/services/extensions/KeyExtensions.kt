package com.sphereon.oid.fed.services.extensions

import com.sphereon.oid.fed.openapi.models.JwkDto
import com.sphereon.oid.fed.persistence.models.Jwk

fun Jwk.toJwkDTO(): JwkDto {
    return JwkDto(
        id = this.id,
        accountId = this.account_id,
        uuid = this.uuid.toString(),
        createdAt = this.created_at.toString(),
        revokedAt = this.revoked_at.toString(),
        revokedReason = this.revoked_reason,
    )
}
