package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.SubordinateMetadata
import kotlinx.serialization.json.JsonElement

data class CreateSubordinateMetadataArgs(
    val account: Account,
    val subordinateId: String,
    val key: String,
    val metadata: JsonElement
)

interface CreateSubordinateMetadataCommandService {
    suspend fun createMetadata(
        account: Account,
        subordinateId: String,
        key: String,
        metadata: JsonElement
    ): IdkResult<SubordinateMetadata, FederationError>
}

interface CreateSubordinateMetadataCommand : Command<CreateSubordinateMetadataArgs, SubordinateMetadata, FederationError>, CreateSubordinateMetadataCommandService {
    companion object { const val COMMAND_ID = "fed.services.subordinate.create-metadata" }
}
