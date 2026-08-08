package com.sphereon.openid.fed.services.mappers

import com.sphereon.openid.fed.openapi.models.Metadata
import com.sphereon.openid.fed.openapi.models.MetadataResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.sphereon.openid.fed.persistence.models.Metadata as MetadataEntity

fun MetadataEntity.toDTO(): Metadata {
    return Metadata(
        id = this.id,
        key = this.key,
        metadata = Json.parseToJsonElement(this.metadata).jsonObject,
        createdAt = this.created_at.toString(),
        accountId = this.account_id
    )
}

fun List<Metadata>.toMetadataResponse() = MetadataResponse(this)
