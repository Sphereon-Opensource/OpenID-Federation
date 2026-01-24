package com.sphereon.openid.fed.services.command.authorityHint

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AuthorityHint

data class DeleteAuthorityHintArgs(val account: Account, val id: String)

interface DeleteAuthorityHintCommandService {
    suspend fun deleteAuthorityHint(account: Account, id: String): IdkResult<AuthorityHint, FederationError>
}

interface DeleteAuthorityHintCommand : Command<DeleteAuthorityHintArgs, AuthorityHint, FederationError>, DeleteAuthorityHintCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.authority-hint.delete"
    }
}
