package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement

/**
 * Arguments for the FindEntityConfigurationByAccount command.
 */
data class FindEntityConfigurationByAccountArgs(
    val account: Account
)

/**
 * Service interface for find entity configuration by account operation.
 */
interface FindEntityConfigurationByAccountCommandService {
    /**
     * Retrieves the Entity Configuration Statement for a given account.
     *
     * @param account The account for which the entity configuration statement is to be retrieved.
     * @return IdkResult containing the EntityConfigurationStatement or an error.
     */
    suspend fun findByAccount(account: Account): IdkResult<EntityConfigurationStatement, FederationError>
}

/**
 * Command to find/retrieve an entity configuration statement for an account.
 */
interface FindEntityConfigurationByAccountCommand : Command<FindEntityConfigurationByAccountArgs, EntityConfigurationStatement, FederationError>, FindEntityConfigurationByAccountCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.entity-configuration.find-by-account"
    }
}
