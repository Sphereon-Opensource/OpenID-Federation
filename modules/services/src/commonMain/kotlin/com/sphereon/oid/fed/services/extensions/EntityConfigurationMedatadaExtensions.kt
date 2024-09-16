package com.sphereon.oid.fed.services.extensions

import com.sphereon.oid.fed.openapi.models.EntityConfigurationMetadataDTO
import com.sphereon.oid.fed.persistence.models.EntityConfigurationMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

fun EntityConfigurationMetadata.toAdminDTO(): EntityConfigurationMetadataDTO {
    return EntityConfigurationMetadataDTO(
        id = this.id,
        key = this.key,
        metadata = Json.parseToJsonElement(this.metadata).jsonObject
    )
}
