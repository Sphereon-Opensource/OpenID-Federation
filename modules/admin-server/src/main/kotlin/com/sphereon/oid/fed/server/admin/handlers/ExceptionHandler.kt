package com.sphereon.oid.fed.server.admin.handlers

import com.sphereon.oid.fed.common.exceptions.ApplicationException
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException

@ControllerAdvice
class ExceptionHandler {
    private val logger = Logger.tag("ExceptionHandler")

    @ExceptionHandler(ApplicationException::class)
    fun handleApplicationExceptions(ex: ApplicationException): ResponseEntity<ErrorResponse> {
        val status = when (ex) {
            is NotFoundException -> HttpStatus.NOT_FOUND
            is EntityAlreadyExistsException -> HttpStatus.CONFLICT
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }

        val errorResponse = ErrorResponse(
            status = status.value(),
            error = status.reasonPhrase,
            message = ex.message ?: "An unexpected error occurred",
            type = ex::class.simpleName ?: "UnknownError"
        )

        when (status) {
            HttpStatus.NOT_FOUND -> logger.debug("Resource not found - Type: ${ex::class.simpleName}, Message: ${ex.message}")
            HttpStatus.CONFLICT -> logger.info("Resource conflict occurred - Type: ${ex::class.simpleName}, Message: ${ex.message}")
            else -> logger.error("Unexpected application exception - Type: ${ex::class.simpleName}, Message: ${ex.message}")
        }

        return ResponseEntity.status(status).body(errorResponse)
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericExceptions(ex: Exception): ResponseEntity<ErrorResponse> {
        val status = when (ex) {
            is NoResourceFoundException -> HttpStatus.NOT_FOUND
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }

        val errorResponse = ErrorResponse(
            status = status.value(),
            error = status.reasonPhrase,
            message = ex.message ?: "An unexpected error occurred",
            type = ex::class.simpleName ?: "UnknownError"
        )

        when (ex) {
            is NoResourceFoundException -> logger.debug("Resource not found - Type: ${ex::class.simpleName}, Message: ${ex.message}")
            else -> logger.error("Unhandled exception occurred - Type: ${ex::class.simpleName}, Message: ${ex.message}")
        }

        return ResponseEntity.status(status).body(errorResponse)
    }
}

data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
    val type: String,
    val timestamp: Long = System.currentTimeMillis()
)