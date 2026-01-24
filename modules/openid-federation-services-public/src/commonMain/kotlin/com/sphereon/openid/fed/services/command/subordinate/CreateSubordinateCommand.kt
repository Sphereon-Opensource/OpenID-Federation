package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateSubordinate
import com.sphereon.openid.fed.openapi.models.Subordinate

data class CreateSubordinateArgs(val account: Account, val subordinateDTO: CreateSubordinate)

interface CreateSubordinateCommandService {
    suspend fun createSubordinate(account: Account, subordinateDTO: CreateSubordinate): IdkResult<Subordinate, FederationError>
}

interface CreateSubordinateCommand : Command<CreateSubordinateArgs, Subordinate, FederationError>, CreateSubordinateCommandService {
    companion object { const val COMMAND_ID = "fed.services.subordinate.create" }
}
