package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.SubordinateJwk

data class CreateSubordinateJwkArgs(val account: Account, val id: String, val jwk: Jwk)

interface CreateSubordinateJwkCommandService {
    suspend fun createSubordinateJwk(account: Account, id: String, jwk: Jwk): IdkResult<SubordinateJwk, FederationError>
}

interface CreateSubordinateJwkCommand : Command<CreateSubordinateJwkArgs, SubordinateJwk, FederationError>, CreateSubordinateJwkCommandService {
    companion object { const val COMMAND_ID = "fed.services.subordinate.create-jwk" }
}
