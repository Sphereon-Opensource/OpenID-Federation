package com.sphereon.openid.fed.services.command.authorityHint

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AuthorityHint

data class FindAuthorityHintsByAccountArgs(val account: Account)

interface FindAuthorityHintsByAccountCommandService {
    suspend fun findByAccount(account: Account): IdkResult<List<AuthorityHint>, FederationError>
}

interface FindAuthorityHintsByAccountCommand : Command<FindAuthorityHintsByAccountArgs, List<AuthorityHint>, FederationError>, FindAuthorityHintsByAccountCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.authority-hint.find-by-account"
    }
}
