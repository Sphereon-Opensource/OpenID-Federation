package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account

/**
 * Arguments for the PublishEntityConfiguration command.
 */
data class PublishEntityConfigurationArgs(
    val account: Account,
    val dryRun: Boolean? = false,
    val kmsKeyRef: String? = null,
    val kid: String? = null
)

/**
 * Service interface for publish entity configuration operation.
 */
interface PublishEntityConfigurationCommandService {
    /**
     * Publishes the entity configuration statement for the specified account.
     * Optionally supports a dry run mode where the resulting JWT is generated but not persisted.
     *
     * @param account The account for which the entity configuration statement is being published.
     * @param dryRun If true, the operation will simulate publishing without persisting the result.
     * @param kmsKeyRef Optional KMS key reference.
     * @param kid Optional key ID.
     * @return IdkResult containing the JWT or an error.
     */
    suspend fun publishByAccount(
        account: Account,
        dryRun: Boolean? = false,
        kmsKeyRef: String? = null,
        kid: String? = null
    ): IdkResult<String, FederationError>
}

/**
 * Command to publish an entity configuration statement for an account.
 */
interface PublishEntityConfigurationCommand : Command<PublishEntityConfigurationArgs, String, FederationError>, PublishEntityConfigurationCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.entity-configuration.publish"
    }
}
