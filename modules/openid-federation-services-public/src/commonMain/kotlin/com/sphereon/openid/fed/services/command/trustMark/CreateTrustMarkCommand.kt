package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkRequest
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkResult

data class CreateTrustMarkArgs(
    val account: Account,
    val body: CreateTrustMarkRequest,
    val currentTimeMillis: Long = System.currentTimeMillis()
)

interface CreateTrustMarkCommandService {
    suspend fun createTrustMark(
        account: Account,
        body: CreateTrustMarkRequest,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): IdkResult<CreateTrustMarkResult, FederationError>
}

interface CreateTrustMarkCommand : Command<CreateTrustMarkArgs, CreateTrustMarkResult, FederationError>, CreateTrustMarkCommandService {
    companion object { const val COMMAND_ID = "fed.services.trust-mark.create" }
}
