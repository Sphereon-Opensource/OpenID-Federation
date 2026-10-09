package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Complete, already-authorized account component candidate. The caller owns entity-wide aggregation;
 * this command neither discovers memberships nor authorizes an enterprise tenant.
 */
@Serializable
data class ReplaceEntityConfigurationComponentsArgs(
    val accountId: String,
    val expectedEntityIdentifier: String,
    val metadata: Map<String, JsonObject>,
    val authorityHints: List<String>,
)

/** Persisted component readback; not a signed publication or an optimistic revision. */
@Serializable
data class ReplacedEntityConfigurationComponents(
    val accountId: String,
    val entityIdentifier: String,
    val metadata: Map<String, JsonObject>,
    val authorityHints: List<String>,
)

interface ReplaceEntityConfigurationComponentsCommand :
    ServiceCommand<ReplaceEntityConfigurationComponentsArgs, ReplacedEntityConfigurationComponents, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.entity-configuration.replace-components"
    }
}
