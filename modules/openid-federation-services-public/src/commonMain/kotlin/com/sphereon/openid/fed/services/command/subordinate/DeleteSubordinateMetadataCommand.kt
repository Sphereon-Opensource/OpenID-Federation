package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.SubordinateMetadata

data class DeleteSubordinateMetadataArgs(val account: Account, val subordinateId: String, val id: String)

interface DeleteSubordinateMetadataCommandService {
    suspend fun deleteSubordinateMetadata(account: Account, subordinateId: String, id: String): IdkResult<SubordinateMetadata, FederationError>
}

interface DeleteSubordinateMetadataCommand : Command<DeleteSubordinateMetadataArgs, SubordinateMetadata, FederationError>, DeleteSubordinateMetadataCommandService {
    companion object { const val COMMAND_ID = "fed.services.subordinate.delete-metadata" }
}
