package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.Subordinate

data class DeleteSubordinateArgs(val account: Account, val id: String)

interface DeleteSubordinateCommandService {
    suspend fun deleteSubordinate(account: Account, id: String): IdkResult<Subordinate, FederationError>
}

interface DeleteSubordinateCommand : Command<DeleteSubordinateArgs, Subordinate, FederationError>, DeleteSubordinateCommandService {
    companion object { const val COMMAND_ID = "fed.services.subordinate.delete" }
}
