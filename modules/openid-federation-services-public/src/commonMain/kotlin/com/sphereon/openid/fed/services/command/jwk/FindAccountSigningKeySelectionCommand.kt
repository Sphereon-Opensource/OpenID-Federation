package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable

/** Internal read command. The caller resolves and authorizes the exact account. */
@Serializable
data class FindAccountSigningKeySelectionArgs(
    val accountId: String,
    val expectedEntityIdentifier: String,
)

interface FindAccountSigningKeySelectionCommand :
    ServiceCommand<FindAccountSigningKeySelectionArgs, AccountSigningKeySelection, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.jwk.find-account-signing-key-selection"
    }
}
