package com.sphereon.openid.fed.services.mappers

import com.sphereon.openid.fed.openapi.models.SubordinateMetadata
import com.sphereon.openid.fed.openapi.models.SubordinateMetadataResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.sphereon.openid.fed.persistence.models.SubordinateMetadata as SubordinateMetadataEntity

fun SubordinateMetadataEntity.toDTO(): SubordinateMetadata {
    return SubordinateMetadata(
        id = this.id,
        key = this.key,
        subordinateId = this.subordinate_id,
        metadata = Json.parseToJsonElement(this.metadata).jsonObject,
        accountId = this.account_id,
        createdAt = this.created_at.toString(),
        deletedAt = this.deleted_at?.toString()
    )
}


fun Array<SubordinateMetadata>.toSubordinateMetadataResponse() = SubordinateMetadataResponse(this.toList())

fun List<SubordinateMetadata>.toSubordinateMetadataResponse() = SubordinateMetadataResponse(this)
