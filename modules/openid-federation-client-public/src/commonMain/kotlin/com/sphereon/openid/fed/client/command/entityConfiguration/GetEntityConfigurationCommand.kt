package com.sphereon.openid.fed.client.command.entityConfiguration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement

/**
 * Arguments for the GetEntityConfiguration command.
 *
 * @param entityIdentifier The entity identifier for which to get the statement.
 */
data class GetEntityConfigurationArgs(
    val entityIdentifier: String
)

/**
 * Service interface for getting entity configuration statements.
 */
interface GetEntityConfigurationCommandService {
    /**
     * Get an Entity Configuration Statement from an entity.
     *
     * @param entityIdentifier The entity identifier for which to get the statement.
     * @return IdkResult containing the EntityConfigurationStatement or an error.
     */
    suspend fun getEntityConfiguration(
        entityIdentifier: String
    ): IdkResult<EntityConfigurationStatement, FederationError>
}

/**
 * Command to get an entity configuration statement.
 */
interface GetEntityConfigurationCommand :
    Command<GetEntityConfigurationArgs, EntityConfigurationStatement, FederationError>,
    GetEntityConfigurationCommandService {

    companion object {
        const val COMMAND_ID = "fed.client.get-entity-configuration"
    }
}
