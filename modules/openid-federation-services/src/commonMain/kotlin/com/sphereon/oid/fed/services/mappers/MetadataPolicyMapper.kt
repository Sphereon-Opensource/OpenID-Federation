package com.sphereon.oid.fed.services.mappers

import com.sphereon.oid.fed.openapi.models.MetadataPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.sphereon.oid.fed.persistence.models.MetadataPolicy as MetadataPolicyEntity

fun MetadataPolicyEntity.toDTO(): MetadataPolicy {
    return MetadataPolicy(
        id = this.id,
        accountId = this.account_id,
        key = this.key,
        policy = Json.decodeFromString<JsonObject>(this.policy),
        createdAt = this.created_at.toString(),
        deletedAt = this.deleted_at.toString()
    )
}
