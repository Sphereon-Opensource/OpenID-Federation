package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError

data class PersistForeignSubordinateStatementArgs(
    val accountId: String,
    val iss: String,
    val sub: String,
    val signedJwt: String,
)

interface PersistForeignSubordinateStatementCommand :
    ServiceCommand<PersistForeignSubordinateStatementArgs, Unit, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.subordinate.persist-foreign-statement"
    }
}
