package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.SubordinateJwk

data class DeleteSubordinateJwkArgs(val account: Account, val id: String, val jwkId: String)

interface DeleteSubordinateJwkCommandService {
    suspend fun deleteSubordinateJwk(account: Account, id: String, jwkId: String): IdkResult<SubordinateJwk, FederationError>
}

interface DeleteSubordinateJwkCommand : Command<DeleteSubordinateJwkArgs, SubordinateJwk, FederationError>, DeleteSubordinateJwkCommandService {
    companion object { const val COMMAND_ID = "fed.services.subordinate.delete-jwk" }
}
