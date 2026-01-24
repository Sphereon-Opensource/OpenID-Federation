package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.models.TrustMark as TrustMarkEntity

data class DeleteTrustMarkArgs(val account: Account, val id: String)

interface DeleteTrustMarkCommandService {
    suspend fun deleteTrustMark(account: Account, id: String): IdkResult<TrustMarkEntity, FederationError>
}

interface DeleteTrustMarkCommand : Command<DeleteTrustMarkArgs, TrustMarkEntity, FederationError>, DeleteTrustMarkCommandService {
    companion object { const val COMMAND_ID = "fed.services.trust-mark.delete" }
}
