package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable

/** Internal coordinates derive from the owning EDK pending administration operation, never public HTTP input. */
@Serializable
data class AtomicSetAccountSigningKeySelectionArgs(
    val accountId: String,
    val expectedEntityIdentifier: String,
    val expectedRevision: Long,
    val operationGeneration: Long,
    val operationToken: String,
    val selectedKeyId: String?,
)

interface AtomicSetAccountSigningKeySelectionCommand : ServiceCommand<AtomicSetAccountSigningKeySelectionArgs, AccountSigningKeySelection, FederationError> {
    companion object { const val COMMAND_ID = "fed.jwk.atomic-set-account-signing-key-selection" }
}
