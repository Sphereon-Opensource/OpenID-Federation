package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.SubordinateMetadata

data class FindSubordinateMetadataArgs(val account: Account, val subordinateId: String)

interface FindSubordinateMetadataCommandService {
    suspend fun findSubordinateMetadata(account: Account, subordinateId: String): IdkResult<Array<SubordinateMetadata>, FederationError>
}

interface FindSubordinateMetadataCommand : Command<FindSubordinateMetadataArgs, Array<SubordinateMetadata>, FederationError>, FindSubordinateMetadataCommandService {
    companion object { const val COMMAND_ID = "fed.services.subordinate.find-metadata" }
}
