package com.sphereon.openid.fed.client.command.entityConfiguration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.HistoricalKey

/**
 * Arguments for the GetHistoricalKeys command.
 *
 * @param entityConfiguration The entity configuration statement from which to retrieve historical keys.
 */
data class GetHistoricalKeysArgs(
    val entityConfiguration: EntityConfigurationStatement
)

/**
 * Service interface for getting historical keys from an entity configuration.
 */
interface GetHistoricalKeysCommandService {
    /**
     * Retrieves the historical keys from the federation entity's historical keys endpoint.
     *
     * @param entityConfiguration The entity configuration statement.
     * @return IdkResult containing the list of HistoricalKey or an error.
     */
    suspend fun getHistoricalKeys(
        entityConfiguration: EntityConfigurationStatement
    ): IdkResult<List<HistoricalKey>, FederationError>
}

/**
 * Command to get historical keys from an entity configuration statement.
 */
interface GetHistoricalKeysCommand :
    Command<GetHistoricalKeysArgs, List<HistoricalKey>, FederationError>,
    GetHistoricalKeysCommandService {

    companion object {
        const val COMMAND_ID = "fed.client.get-historical-keys"
    }
}
