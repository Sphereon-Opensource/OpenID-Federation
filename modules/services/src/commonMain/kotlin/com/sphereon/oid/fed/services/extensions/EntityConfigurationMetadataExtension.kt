package com.sphereon.oid.fed.services.extensions

import com.sphereon.oid.fed.openapi.models.EntityConfigurationMetadataDTO
import com.sphereon.oid.fed.persistence.models.EntityConfigurationMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject


fun EntityConfigurationMetadata.toEntityConfigurationMetadataDTO(): EntityConfigurationMetadataDTO {
    return EntityConfigurationMetadataDTO(
        id = this.id,
        key = this.key,
        accountId = this.account_id,
        metadata = Json.parseToJsonElement(this.metadata).jsonObject,
        createdAt = this.created_at.toString(),
        deletedAt = this.deleted_at?.toString()
    )
}
