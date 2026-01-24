package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.openid.fed.openapi.models.TrustMarkType

data class CreateTrustMarkTypeArgs(val account: Account, val createDto: CreateTrustMarkType)

interface CreateTrustMarkTypeCommandService {
    suspend fun createTrustMarkType(account: Account, createDto: CreateTrustMarkType): IdkResult<TrustMarkType, FederationError>
}

interface CreateTrustMarkTypeCommand : Command<CreateTrustMarkTypeArgs, TrustMarkType, FederationError>, CreateTrustMarkTypeCommandService {
    companion object { const val COMMAND_ID = "fed.services.trust-mark.create-type" }
}
