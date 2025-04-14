package com.sphereon.oid.fed.server.handlers

import com.sphereon.oid.fed.common.exceptions.federation.FederationException
import com.sphereon.oid.fed.common.exceptions.federation.InvalidClientException
import com.sphereon.oid.fed.common.exceptions.federation.InvalidIssuerException
import com.sphereon.oid.fed.common.exceptions.federation.InvalidMetadataException
import com.sphereon.oid.fed.common.exceptions.federation.InvalidRequestException
import com.sphereon.oid.fed.common.exceptions.federation.InvalidSubjectException
import com.sphereon.oid.fed.common.exceptions.federation.InvalidTrustAnchorException
import com.sphereon.oid.fed.common.exceptions.federation.InvalidTrustChainException
import com.sphereon.oid.fed.common.exceptions.federation.NotFoundException
import com.sphereon.oid.fed.common.exceptions.federation.ServerErrorException
import com.sphereon.oid.fed.common.exceptions.federation.TemporarilyUnavailableException
import com.sphereon.oid.fed.common.exceptions.federation.UnsupportedParameterException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice
class ExceptionHandler {
    private val logger = Logger.tag("ExceptionHandler")

    @ExceptionHandler(FederationException::class)
    fun handleFederationExceptions(ex: FederationException): ResponseEntity<ErrorResponse> {
        when (ex) {
            is InvalidRequestException -> logger.debug("Invalid Request - Type: ${ex::class.simpleName}, Message: ${ex.errorDescription}")
            is InvalidClientException -> logger.warn("Invalid Client - Type: ${ex::class.simpleName}, Message: ${ex.errorDescription}")
            is InvalidIssuerException -> logger.debug("Invalid Issuer - Type: ${ex::class.simpleName}, Message: ${ex.errorDescription}")
            is InvalidSubjectException -> logger.debug("Invalid Subject - Type: ${ex::class.simpleName}, Message: ${ex.errorDescription}")
            is InvalidTrustAnchorException -> logger.debug("Invalid Trust Anchor - Type: ${ex::class.simpleName}, Message: ${ex.errorDescription}")
            is InvalidTrustChainException -> logger.debug("Invalid Trust Chain - Type: ${ex::class.simpleName}, Message: ${ex.errorDescription}")
            is InvalidMetadataException -> logger.debug("Invalid Metadata - Type: ${ex::class.simpleName}, Message: ${ex.errorDescription}")
            is NotFoundException -> logger.debug("Not Found - Type: ${ex::class.simpleName}, Message: ${ex.errorDescription}")
            is ServerErrorException -> logger.error("Server Error - Type: ${ex::class.simpleName}, Message: ${ex.errorDescription}")
            is TemporarilyUnavailableException -> logger.warn("Temporarily Unavailable - Type: ${ex::class.simpleName}, Message: ${ex.errorDescription}")
            is UnsupportedParameterException -> logger.debug("Unsupported Parameter - Type: ${ex::class.simpleName}, Message: ${ex.errorDescription}")
            else -> logger.error("Unexpected federation exception - Type: ${ex::class.simpleName}, Message: ${ex.errorDescription}")
        }

        return ResponseEntity
            .status(HttpStatus.resolve(ex.httpStatus)!!)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                ErrorResponse(
                    error = ex.error,
                    errorDescription = ex.errorDescription,
                )
            )
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericExceptions(ex: Exception): ResponseEntity<ErrorResponse> {
        val error = ServerErrorException(ex.message ?: "Internal Server Error")
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                ErrorResponse(
                    error = error.error,
                    errorDescription = error.errorDescription,
                )
            )
    }
}
