package com.sphereon.openid.fed.services.mappers

import com.sphereon.openid.fed.openapi.models.Subordinate
import com.sphereon.openid.fed.openapi.models.SubordinatesResponse
import com.sphereon.openid.fed.persistence.models.Subordinate as SubordinateEntity

fun SubordinateEntity.toDTO(): Subordinate {
    return Subordinate(
        id = this.id,
        accountId = this.account_id,
        identifier = this.identifier,
        createdAt = this.created_at.toString(),
        deletedAt = this.deleted_at?.toString()
    )
}


fun Array<SubordinateEntity>.toDTOs(): Array<Subordinate> = this.map { it.toDTO() }.toTypedArray()
fun Array<Subordinate>.toSubordinatesResponse() = SubordinatesResponse(this.toList())
