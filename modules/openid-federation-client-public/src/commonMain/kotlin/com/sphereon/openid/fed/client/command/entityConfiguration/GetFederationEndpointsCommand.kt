package com.sphereon.openid.fed.client.command.entityConfiguration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.FederationEntityMetadata

/**
 * Arguments for the GetFederationEndpoints command.
 *
 * @param entityConfiguration The entity configuration statement from which to extract endpoints.
 */
data class GetFederationEndpointsArgs(
    val entityConfiguration: EntityConfigurationStatement
)

/**
 * Service interface for getting federation endpoints from an entity configuration.
 */
interface GetFederationEndpointsCommandService {
    /**
     * Gets federation endpoints from an EntityConfigurationStatement.
     *
     * @param entityConfiguration The entity configuration statement.
     * @return IdkResult containing the FederationEntityMetadata or an error.
     */
    suspend fun getFederationEndpoints(
        entityConfiguration: EntityConfigurationStatement
    ): IdkResult<FederationEntityMetadata, FederationError>
}

/**
 * Command to get federation endpoints from an entity configuration statement.
 */
interface GetFederationEndpointsCommand :
    Command<GetFederationEndpointsArgs, FederationEntityMetadata, FederationError>,
    GetFederationEndpointsCommandService {

    companion object {
        const val COMMAND_ID = "fed.client.get-federation-endpoints"
    }
}
