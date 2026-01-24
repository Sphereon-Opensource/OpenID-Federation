package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account

data class PublishSubordinateStatementArgs(
    val account: Account,
    val id: String,
    val dryRun: Boolean? = false,
    val kmsKeyRef: String? = null,
    val kid: String? = null
)

interface PublishSubordinateStatementCommandService {
    suspend fun publishSubordinateStatement(
        account: Account,
        id: String,
        dryRun: Boolean? = false,
        kmsKeyRef: String? = null,
        kid: String? = null
    ): IdkResult<String, FederationError>
}

interface PublishSubordinateStatementCommand : Command<PublishSubordinateStatementArgs, String, FederationError>, PublishSubordinateStatementCommandService {
    companion object { const val COMMAND_ID = "fed.services.subordinate.publish-statement" }
}
