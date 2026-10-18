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
        val mapped = mapFederationErrorCode(error.errorCode) ?: byStatus(error.httpStatusValue)
        return of(mapped.error, description, mapped.status)
    }

    fun fromIdkError(error: IdkError): GenericHttpResponse {
        val metaCode = error.meta["errorCode"] as? String ?: error.code
        val status = (error.meta["httpStatus"] as? Number)?.toInt() ?: 500
        val description = error.message.defaultMessage
        val mapped = mapFederationErrorCode(metaCode) ?: byStatus(status)
        return of(mapped.error, description, mapped.status)
    }

    /** Accept [FederationError] or [IdkError] from service command results. */
    fun fromServiceError(error: Any): GenericHttpResponse =
        when (error) {
            is FederationError -> fromFederationError(error)
            is IdkError -> fromIdkError(error)
            else -> serverError(error.toString())
        }

    private data class MappedError(val error: String, val status: Int)

    /** A §8.9 error code for a Library error code, or null when the code has no federation equivalent. */
    private fun mapFederationErrorCode(code: String): MappedError? =
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
            else -> null
        }

    /**
     * Library error codes without a §8.9 equivalent are not sent as the error code. The error's own HTTP status
     * decides: a server-side failure is a server_error with a 5xx status (§8.9), a client-side one the closest
     * client error.
     */
    private fun byStatus(status: Int): MappedError =
        when (status) {
            401 -> MappedError("invalid_client", 401)
            404 -> MappedError("not_found", 404)
            503 -> MappedError("temporarily_unavailable", 503)
            in 500..599 -> MappedError("server_error", status)
            else -> MappedError("invalid_request", 400)
        }
}
