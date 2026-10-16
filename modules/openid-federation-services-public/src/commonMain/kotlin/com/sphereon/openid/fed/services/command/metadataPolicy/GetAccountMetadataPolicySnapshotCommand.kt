package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable

@Serializable
data class GetAccountMetadataPolicySnapshotArgs(val accountId: String, val expectedEntityIdentifier: String, val entityType: String)

interface GetAccountMetadataPolicySnapshotCommand : ServiceCommand<GetAccountMetadataPolicySnapshotArgs, AccountMetadataPolicySnapshot, FederationError> {
    companion object { const val COMMAND_ID = "fed.metadata-policy.snapshot" }
}
