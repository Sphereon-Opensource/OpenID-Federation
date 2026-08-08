package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Arguments for validating an OpenID4VP Authorization Request against federation CV metadata.
 */
data class ValidateCredentialVerifierRequestArgs(
    /** Full entity metadata or the `openid_credential_verifier` object from Resolved Metadata. */
    val entityMetadata: JsonObject,
    val requestUri: String? = null,
    val responseUri: String? = null,
    val redirectUri: String? = null,
    /** Authorization Request / client_metadata.jwks (ignored when federation jwks present). */
    val clientMetadata: JsonObject? = null,
    /** Authorization Request `dcql_query` value. */
    val requestDcqlQuery: JsonElement? = null
)

@Serializable
data class CredentialVerifierRequestValidationResult(
    val valid: Boolean,
    val endpointChecks: Map<String, Boolean> = emptyMap(),
    val jwksFromFederation: Boolean = false,
    val jwksPresent: Boolean = false,
    val dcqlValid: Boolean = true,
    val detail: String? = null
)

interface ValidateCredentialVerifierRequestCommandService {
    suspend fun validateCredentialVerifierRequest(
        entityMetadata: JsonObject,
        requestUri: String? = null,
        responseUri: String? = null,
        redirectUri: String? = null,
        clientMetadata: JsonObject? = null,
        requestDcqlQuery: JsonElement? = null
    ): IdkResult<CredentialVerifierRequestValidationResult, FederationError>
}

/**
 * Validates a Credential Verifier Authorization Request against federation metadata:
 * endpoints, JWKS source preference, and dcql_queries constraints.
 */
interface ValidateCredentialVerifierRequestCommand :
    Command<ValidateCredentialVerifierRequestArgs, CredentialVerifierRequestValidationResult, FederationError>,
    ValidateCredentialVerifierRequestCommandService {
    companion object {
        const val COMMAND_ID = "fed.wallet.validate-credential-verifier-request"
    }
}
