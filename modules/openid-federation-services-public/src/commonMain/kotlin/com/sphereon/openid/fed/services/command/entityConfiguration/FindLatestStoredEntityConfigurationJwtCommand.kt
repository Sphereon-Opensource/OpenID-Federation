package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError

data class FindLatestStoredEntityConfigurationJwtArgs(val accountId: String)

data class StoredEntityConfigurationJwt(val compact: String?)

interface FindLatestStoredEntityConfigurationJwtCommand :
    ServiceCommand<FindLatestStoredEntityConfigurationJwtArgs, StoredEntityConfigurationJwt, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.entity-configuration.find-latest-stored-jwt"
    }
}
