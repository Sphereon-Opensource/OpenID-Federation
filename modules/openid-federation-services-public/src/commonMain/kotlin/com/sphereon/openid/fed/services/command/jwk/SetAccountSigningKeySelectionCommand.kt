package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable

/** Internal command. Caller resolves/authorizes the exact account; null selection explicitly clears. */
@Serializable
data class SetAccountSigningKeySelectionArgs(
    val accountId: String,
    val expectedEntityIdentifier: String,
    val expectedRevision: Long,
    val selectedKeyId: String?,
)

@Serializable
data class AccountSigningKeySelection(
    val accountId: String,
    val entityIdentifier: String,
    val selectedKeyId: String?,
    val revision: Long,
)

interface SetAccountSigningKeySelectionCommand :
    ServiceCommand<SetAccountSigningKeySelectionArgs, AccountSigningKeySelection, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.jwk.set-account-signing-key-selection"
    }
}
