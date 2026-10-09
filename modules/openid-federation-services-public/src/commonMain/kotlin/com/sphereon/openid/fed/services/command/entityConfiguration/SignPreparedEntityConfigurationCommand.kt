package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * An already-prepared Entity Configuration payload and a server-selected persisted JWK row ID.
 * The caller owns account authorization, exact statement preparation and key selection.
 * The implementation must independently validate the statement and selected persisted key
 * against authoritative account/key records; caller authorization or preparation does not
 * replace exact account identity, key ownership, revocation and public-material checks.
 * The JsonObject is the sole statement representation; no fixed DTO or claims side bag is used.
 */
@Serializable
data class SignPreparedEntityConfigurationArgs(
    val accountId: String,
    val selectedKeyId: String,
    val statement: JsonObject,
    val expectedSelectionRevision: Long,
)

/** Internal signing command. Success is the compact JWT, not publication or activation evidence. */
interface SignPreparedEntityConfigurationCommand :
    ServiceCommand<SignPreparedEntityConfigurationArgs, String, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.entity-configuration.sign-prepared"
    }
}
