package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Trusted internal mutation coordinates; the owning EDK command derives its generation and operation token. */
@Serializable
data class AtomicReplaceMetadataPolicyArgs(
    val accountId: String,
    val expectedEntityIdentifier: String,
    val entityType: String,
    val expectedRevision: Long,
    val operationGeneration: Long,
    val operationToken: String,
    val expectedPolicy: JsonObject?,
    val policy: JsonObject?,
)

@Serializable
data class AccountMetadataPolicySnapshot(
    val accountId: String,
    val entityIdentifier: String,
    val entityType: String,
    val revision: Long,
    val policy: JsonObject?,
    val operationGeneration: Long?,
)

interface AtomicReplaceMetadataPolicyCommand : ServiceCommand<AtomicReplaceMetadataPolicyArgs, AccountMetadataPolicySnapshot, FederationError> {
    companion object { const val COMMAND_ID = "fed.metadata-policy.atomic-replace" }
}
