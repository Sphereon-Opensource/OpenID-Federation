package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.SubordinateJwk

data class GetSubordinateJwksArgs(val account: Account, val id: String)

interface GetSubordinateJwksCommandService {
    suspend fun getSubordinateJwks(account: Account, id: String): IdkResult<Array<SubordinateJwk>, FederationError>
}

interface GetSubordinateJwksCommand : Command<GetSubordinateJwksArgs, Array<SubordinateJwk>, FederationError>, GetSubordinateJwksCommandService {
    companion object { const val COMMAND_ID = "fed.services.subordinate.get-jwks" }
}
