package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.Serializable

@Serializable
data class SignAccountRequestJwtArgs(
    val tenantId: String,
    val typ: String,
    val claims: JsonObject,
    val kid: String? = null,
)

/** Signs caller-provided request claims with an active Federation Entity Key. */
interface SignAccountRequestJwtCommand : ServiceCommand<SignAccountRequestJwtArgs, String, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.jwk.sign-request-jwt"
    }
}
