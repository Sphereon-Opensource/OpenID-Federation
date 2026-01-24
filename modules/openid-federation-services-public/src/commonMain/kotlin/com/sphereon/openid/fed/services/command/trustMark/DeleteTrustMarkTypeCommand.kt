package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.TrustMarkType

data class DeleteTrustMarkTypeArgs(val account: Account, val id: String)

interface DeleteTrustMarkTypeCommandService {
    suspend fun deleteTrustMarkType(account: Account, id: String): IdkResult<TrustMarkType, FederationError>
}

interface DeleteTrustMarkTypeCommand : Command<DeleteTrustMarkTypeArgs, TrustMarkType, FederationError>, DeleteTrustMarkTypeCommandService {
    companion object { const val COMMAND_ID = "fed.services.trust-mark.delete-type" }
}
