package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.MetadataPolicy
import kotlinx.serialization.json.JsonElement

/**
 * Arguments for the CreateMetadataPolicy command.
 */
data class CreateMetadataPolicyArgs(
    val account: Account,
    val key: String,
    val policy: JsonElement
)

/**
 * Service interface for create metadata policy operation.
 */
interface CreateMetadataPolicyCommandService {
    /**
     * Creates a new entity configuration metadata policy entry for a specified account and key.
     *
     * @param account The account for which the metadata policy is being created.
     * @param key The unique key representing the metadata policy.
     * @param policy The policy content to be associated with the account and key.
     * @return IdkResult containing the created MetadataPolicy or an error.
     */
    suspend fun createPolicy(account: Account, key: String, policy: JsonElement): IdkResult<MetadataPolicy, FederationError>
}

/**
 * Command to create a new metadata policy entry for an account.
 */
interface CreateMetadataPolicyCommand : Command<CreateMetadataPolicyArgs, MetadataPolicy, FederationError>, CreateMetadataPolicyCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.metadata-policy.create"
    }
}
