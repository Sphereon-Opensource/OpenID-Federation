package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable

/**
 * Internal read command. Resolves the account's persisted signing-key selection to the exact active key that
 * signs this account's statements. There is no fallback: without a complete selected key nothing is signed.
 */
@Serializable
data class ResolveAccountSigningKeyArgs(
    val accountId: String,
    val expectedEntityIdentifier: String,
)

@Serializable
data class AccountSigningKey(
    val accountId: String,
    val entityIdentifier: String,
    val keyId: String,
    val kid: String,
    val alg: String,
    val kmsKeyRef: String,
    val kms: String,
    val selectionRevision: Long,
)

interface ResolveAccountSigningKeyCommand :
    ServiceCommand<ResolveAccountSigningKeyArgs, AccountSigningKey, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.jwk.resolve-account-signing-key"
    }
}
