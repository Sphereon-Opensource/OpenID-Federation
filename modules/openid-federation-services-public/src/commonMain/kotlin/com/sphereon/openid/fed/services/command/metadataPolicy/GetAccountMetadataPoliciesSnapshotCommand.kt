package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class GetAccountMetadataPoliciesSnapshotArgs(val accountId: String, val expectedEntityIdentifier: String)

/** Complete body and durable role witnesses from one account-locked transaction, including inactive/custom roles. */
@Serializable
data class AccountMetadataPoliciesSnapshot(
    val accountId: String,
    val entityIdentifier: String,
    val metadataPolicy: JsonObject,
    val metadataPolicyRevisions: Map<String, Long>,
    val operationGenerations: Map<String, Long?>,
)

interface GetAccountMetadataPoliciesSnapshotCommand : ServiceCommand<GetAccountMetadataPoliciesSnapshotArgs, AccountMetadataPoliciesSnapshot, FederationError> {
    companion object { const val COMMAND_ID = "fed.metadata-policy.snapshot-all" }
}
