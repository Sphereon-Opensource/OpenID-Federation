package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.TrustMarkType

data class FindTrustMarkTypeByIdArgs(val account: Account, val id: String)

interface FindTrustMarkTypeByIdCommandService {
    suspend fun findById(account: Account, id: String): IdkResult<TrustMarkType, FederationError>
}

interface FindTrustMarkTypeByIdCommand : Command<FindTrustMarkTypeByIdArgs, TrustMarkType, FederationError>, FindTrustMarkTypeByIdCommandService {
    companion object { const val COMMAND_ID = "fed.services.trust-mark.find-type-by-id" }
}
