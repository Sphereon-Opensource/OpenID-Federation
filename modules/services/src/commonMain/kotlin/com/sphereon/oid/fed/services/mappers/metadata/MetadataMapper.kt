package com.sphereon.oid.fed.services.mappers.metadata

import com.sphereon.oid.fed.openapi.models.Metadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.sphereon.oid.fed.persistence.models.Metadata as MetadataEntity

fun MetadataEntity.toDTO(): Metadata {
    return Metadata(
        id = this.id,
        key = this.key,
        metadata = Json.parseToJsonElement(this.metadata).jsonObject,
        createdAt = this.created_at.toString(),
        accountId = this.account_id
    )
}
