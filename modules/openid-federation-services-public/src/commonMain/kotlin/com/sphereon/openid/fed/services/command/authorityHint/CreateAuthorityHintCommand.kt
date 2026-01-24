package com.sphereon.openid.fed.services.command.authorityHint

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AuthorityHint

data class CreateAuthorityHintArgs(val account: Account, val identifier: String)

interface CreateAuthorityHintCommandService {
    suspend fun createAuthorityHint(account: Account, identifier: String): IdkResult<AuthorityHint, FederationError>
}

interface CreateAuthorityHintCommand : Command<CreateAuthorityHintArgs, AuthorityHint, FederationError>, CreateAuthorityHintCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.authority-hint.create"
    }
}
