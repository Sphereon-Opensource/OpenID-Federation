package com.sphereon.oid.fed.services.mappers

import com.sphereon.oid.fed.openapi.models.SubordinateMetadataDTO
import com.sphereon.oid.fed.persistence.models.SubordinateMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

fun SubordinateMetadata.toSubordinateMetadataDTO(): SubordinateMetadataDTO {
    return SubordinateMetadataDTO(
        id = this.id,
        key = this.key,
        subordinateId = this.subordinate_id,
        metadata = Json.parseToJsonElement(this.metadata).jsonObject,
        accountId = this.account_id,
        createdAt = this.created_at.toString(),
    )
}
