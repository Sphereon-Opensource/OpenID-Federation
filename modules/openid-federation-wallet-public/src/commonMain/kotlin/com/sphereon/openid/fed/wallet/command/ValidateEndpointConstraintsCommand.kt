package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Arguments for validating endpoint constraints.
 */
data class ValidateEndpointConstraintsArgs(
    val entityMetadata: JsonObject,
    val requestUri: String? = null,
    val responseUri: String? = null,
    val redirectUri: String? = null,
    val entityType: String = "openid_credential_verifier"
)

/**
 * Result of endpoint constraint validation.
 */
@Serializable
data class EndpointConstraintsResult(
    val valid: Boolean,
    val validatedEndpoints: Map<String, Boolean>
)

/**
 * Service interface for the ValidateEndpointConstraints command.
 */
interface ValidateEndpointConstraintsCommandService {
    suspend fun validateEndpointConstraints(
        entityMetadata: JsonObject,
        requestUri: String? = null,
        responseUri: String? = null,
        redirectUri: String? = null,
        entityType: String = "openid_credential_verifier"
    ): IdkResult<EndpointConstraintsResult, FederationError>
}

/**
 * Command to validate that a credential verifier's endpoints are pre-registered
 * in its federation metadata.
 *
 * Per the OpenID Federation Wallet Architecture spec Section 7.3, Wallets MUST verify
 * that the Credential Verifier's request_uris, response_uris, and redirect_uris
 * are registered in the entity's metadata obtained through federation.
 */
interface ValidateEndpointConstraintsCommand :
    Command<ValidateEndpointConstraintsArgs, EndpointConstraintsResult, FederationError>,
    ValidateEndpointConstraintsCommandService {
    companion object {
        const val COMMAND_ID = "fed.wallet.validate-endpoint-constraints"
    }
}
