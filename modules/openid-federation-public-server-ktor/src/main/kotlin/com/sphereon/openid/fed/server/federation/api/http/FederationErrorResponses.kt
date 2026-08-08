package com.sphereon.openid.fed.server.federation.api.http

import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.response.jsonResponse
import com.sphereon.openid.fed.common.exceptions.federation.FederationException
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.ErrorResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * OIDFed 1.1 §8.9 error responses: JSON body with `error` + `error_description`.
 *
 * Do not use IDK [com.sphereon.core.api.http.response.errorResponse] here — that emits
 * `{ "error": { "code", "message" } }`, which is not the federation protocol shape.
 */
object FederationErrorResponses {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun of(
        error: String,
        errorDescription: String,
        statusCode: Int,
    ): GenericHttpResponse =
        jsonResponse(
            statusCode,
            json.encodeToString(
                ErrorResponse.serializer(),
                ErrorResponse(error = error, errorDescription = errorDescription),
            ),
        )

    fun invalidRequest(description: String) =
        of("invalid_request", description, 400)

    fun invalidClient(description: String) =
        of("invalid_client", description, 401)

    fun invalidIssuer(description: String) =
        of("invalid_issuer", description, 404)

    fun invalidSubject(description: String) =
        of("invalid_subject", description, 404)

    fun invalidTrustAnchor(description: String) =
        of("invalid_trust_anchor", description, 404)

    fun invalidTrustChain(description: String) =
        of("invalid_trust_chain", description, 400)

    fun invalidMetadata(description: String) =
        of("invalid_metadata", description, 400)

    fun notFound(description: String) =
        of("not_found", description, 404)

    fun serverError(description: String) =
        of("server_error", description, 500)

    fun temporarilyUnavailable(description: String) =
        of("temporarily_unavailable", description, 503)

    fun unsupportedParameter(description: String) =
        of("unsupported_parameter", description, 400)

    fun fromException(ex: FederationException): GenericHttpResponse =
        of(ex.error, ex.errorDescription, ex.httpStatus)

    /**
     * Map service-layer [FederationError] to §8.9 codes.
     */
    fun fromFederationError(error: FederationError): GenericHttpResponse {
        val description = error.message.defaultMessage
        val mapped = mapFederationErrorCode(error.errorCode)
        return of(mapped.error, description, mapped.status)
    }

    fun fromIdkError(error: IdkError): GenericHttpResponse {
        val metaCode = error.meta["errorCode"] as? String ?: error.code
        val status = (error.meta["httpStatus"] as? Number)?.toInt() ?: 500
        val description = error.message.defaultMessage
        val mapped = mapFederationErrorCode(metaCode)
        val code = if (mapped.error != metaCode || metaCode in SPEC_ERROR_CODES) {
            mapped.error
        } else {
            mapStatusToDefaultError(status)
        }
        val http = if (mapped.error != metaCode || metaCode in SPEC_ERROR_CODES) mapped.status else status
        return of(code, description, http)
    }

    /** Accept [FederationError] or [IdkError] from service command results. */
    fun fromServiceError(error: Any): GenericHttpResponse =
        when (error) {
            is FederationError -> fromFederationError(error)
            is IdkError -> fromIdkError(error)
            else -> serverError(error.toString())
        }

    private data class MappedError(val error: String, val status: Int)

    private fun mapFederationErrorCode(code: String): MappedError =
        when (code) {
            "invalid_request", "missing_parameter" -> MappedError("invalid_request", 400)
            "unsupported_parameter" -> MappedError("unsupported_parameter", 400)
            "invalid_client", "unauthorized" -> MappedError("invalid_client", 401)
            "invalid_issuer" -> MappedError("invalid_issuer", 404)
            "invalid_subject", "subordinate_not_found", "entity_not_found" ->
                MappedError("invalid_subject", 404)
            "invalid_trust_anchor" -> MappedError("invalid_trust_anchor", 404)
            "invalid_trust_chain", "no_trust_chain", "trust_chain_validation_failed" ->
                MappedError("invalid_trust_chain", 400)
            "invalid_metadata", "invalid_entity_configuration", "metadata_policy_application_error" ->
                MappedError("invalid_metadata", 400)
            "not_found", "account_not_found", "trust_mark_not_found",
            "received_trust_mark_not_found", "trust_mark_type_not_found",
            "key_not_found", "signing_key_not_found" ->
                MappedError("not_found", 404)
            "temporarily_unavailable", "federation_network_error" ->
                MappedError("temporarily_unavailable", 503)
            "server_error", "jwt_creation_failed", "key_resolution_failed" ->
                MappedError("server_error", 500)
            else -> MappedError(code, 400)
        }

    private fun mapStatusToDefaultError(status: Int): String =
        when (status) {
            400 -> "invalid_request"
            401 -> "invalid_client"
            404 -> "not_found"
            503 -> "temporarily_unavailable"
            in 500..599 -> "server_error"
            else -> "invalid_request"
        }

    private val SPEC_ERROR_CODES = setOf(
        "invalid_request", "invalid_client", "invalid_issuer", "invalid_subject",
        "invalid_trust_anchor", "invalid_trust_chain", "invalid_metadata",
        "not_found", "server_error", "temporarily_unavailable", "unsupported_parameter",
        "missing_parameter", "subordinate_not_found", "entity_not_found",
        "no_trust_chain", "trust_chain_validation_failed", "account_not_found",
    )
}
