package com.sphereon.oid.fed.common.exceptions.federation

import io.ktor.http.*

sealed class FederationException(
    val error: String,
    val errorDescription: String,
    val httpStatus: Int
) : RuntimeException("$error: $errorDescription")

class InvalidRequestException(errorDescription: String) :
    FederationException(
        error = "invalid_request",
        errorDescription = errorDescription,
        httpStatus = HttpStatusCode.BadRequest.value,
    )

class InvalidClientException(errorDescription: String) : FederationException(
    error = "invalid_client",
    errorDescription = errorDescription,
    httpStatus = HttpStatusCode.Unauthorized.value,
)

class InvalidIssuerException(errorDescription: String) : FederationException(
    error = "invalid_issuer",
    errorDescription = errorDescription,
    httpStatus = HttpStatusCode.NotFound.value,
)

class InvalidSubjectException(errorDescription: String) : FederationException(
    error = "invalid_subject",
    errorDescription = errorDescription,
    httpStatus = HttpStatusCode.NotFound.value,
)

class InvalidTrustAnchorException(errorDescription: String) : FederationException(
    error = "invalid_trust_anchor",
    errorDescription = errorDescription,
    httpStatus = HttpStatusCode.NotFound.value,
)

class InvalidTrustChainException(errorDescription: String) : FederationException(
    error = "invalid_trust_chain",
    errorDescription = errorDescription,
    httpStatus = HttpStatusCode.BadRequest.value,
)

class InvalidMetadataException(errorDescription: String) : FederationException(
    error = "invalid_metadata",
    errorDescription = errorDescription,
    httpStatus = HttpStatusCode.BadRequest.value,
)

class NotFoundException(errorDescription: String) : FederationException(
    error = "not_found",
    errorDescription = errorDescription,
    httpStatus = HttpStatusCode.NotFound.value,
)

class ServerErrorException(errorDescription: String) : FederationException(
    error = "server_error",
    errorDescription = errorDescription,
    httpStatus = HttpStatusCode.InternalServerError.value,
)

class TemporarilyUnavailableException(errorDescription: String) : FederationException(
    error = "temporarily_unavailable",
    errorDescription = errorDescription,
    httpStatus = HttpStatusCode.ServiceUnavailable.value,
)

class UnsupportedParameterException(errorDescription: String) : FederationException(
    error = "unsupported_parameter",
    errorDescription = errorDescription,
    httpStatus = HttpStatusCode.BadRequest.value,
)
