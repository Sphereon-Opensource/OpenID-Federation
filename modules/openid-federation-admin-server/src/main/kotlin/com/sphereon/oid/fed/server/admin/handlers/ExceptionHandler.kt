package com.sphereon.oid.fed.server.admin.handlers

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.sphereon.oid.fed.common.exceptions.ApplicationException
import com.sphereon.oid.fed.common.exceptions.BadRequestException
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import jakarta.servlet.ServletException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
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
            is BadRequestException -> HttpStatus.BAD_REQUEST
            is EntityAlreadyExistsException -> HttpStatus.CONFLICT
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }

        val errorResponse = createErrorResponse(status, ex, ex.message)

        when (status) {
            HttpStatus.BAD_REQUEST -> logger.debug("Bad Request - Type: ${ex::class.simpleName}, Message: ${ex.message}")
            HttpStatus.NOT_FOUND -> logger.debug("Resource not found - Type: ${ex::class.simpleName}, Message: ${ex.message}")
            HttpStatus.CONFLICT -> logger.info("Resource conflict occurred - Type: ${ex::class.simpleName}, Message: ${ex.message}")
            else -> logger.error("Unexpected application exception - Type: ${ex::class.simpleName}, Message: ${ex.message}")
        }

        return ResponseEntity.status(status).body(errorResponse)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMissingField(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        val errorResponse = when (val cause = ex.cause) {
            is InvalidFormatException -> {
                val fieldName = cause.path.joinToString(".") { it.fieldName }
                val expectedType = cause.targetType.simpleName
                createErrorResponse(
                    HttpStatus.BAD_REQUEST,
                    ex,
                    "Invalid value for field '$fieldName'. Expected type: $expectedType"
                )
            }

            is MismatchedInputException -> {
                val fieldName = cause.path.joinToString(".") { it.fieldName }
                val expectedType = cause.targetType.simpleName
                createErrorResponse(
                    HttpStatus.BAD_REQUEST,
                    ex,
                    "Invalid value for field '$fieldName'. Expected type: $expectedType"
                )
            }

            is JsonParseException -> {
                createErrorResponse(HttpStatus.BAD_REQUEST, ex, cause.originalMessage)
            }

            is Throwable -> {
                createErrorResponse(HttpStatus.BAD_REQUEST, ex, cause.message)
            }

            else -> {
                createErrorResponse(HttpStatus.BAD_REQUEST, ex, "An unexpected error occurred")
            }
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse)
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericExceptions(ex: Exception): ResponseEntity<ErrorResponse> {
        val status = when (ex) {
            is BindException -> HttpStatus.BAD_REQUEST
            is ServletException -> HttpStatus.BAD_REQUEST
            is NoResourceFoundException -> HttpStatus.NOT_FOUND
            else -> HttpStatus.BAD_REQUEST
        }

        val errorResponse = createErrorResponse(status, ex)

        when (ex) {
            is NoResourceFoundException -> logger.debug("Resource not found - Type: ${ex::class.simpleName}, Message: ${ex.message}")
            is BindException -> logger.debug("Validation error - Type: ${ex::class.simpleName}, Message: ${ex.bindingResult}")
            else -> logger.error("Unhandled exception occurred - Type: ${ex::class.simpleName}, Message: ${ex.message}")
        }

        return ResponseEntity.status(status).body(errorResponse)
    }


    private fun createErrorResponse(status: HttpStatus, ex: Throwable, message: String? = null): ErrorResponse {
        return when (ex) {
            is BindException -> {
                logger.debug("Validation error - Type: ${ex::class.simpleName}, Message: ${ex.bindingResult}")
                return createBindExceptionErrorResponse(status, ex, message)
            }

            else -> createGenericErrorResponse(status, ex, message)
        }
    }

    private fun createBindExceptionErrorResponse(
        status: HttpStatus,
        ex: BindException,
        message: String? = null
    ): ErrorResponse {
        val fieldErrors = ex.bindingResult.fieldErrors.map { error ->
            val field = error.field
            val defaultMessage = error.defaultMessage ?: "is invalid"
            "$field $defaultMessage"
        }

        val formattedMessage = if (fieldErrors.isNotEmpty()) {
            fieldErrors.joinToString("; ")
        } else {
            message ?: ex.message ?: "Validation error"
        }

        return ErrorResponse(
            status = status.value(),
            error = status.reasonPhrase,
            message = formattedMessage,
            type = ex::class.simpleName ?: "UnknownError"
        )
    }

    private fun createGenericErrorResponse(status: HttpStatus, ex: Throwable, message: String? = null): ErrorResponse {
        return ErrorResponse(
            status = status.value(),
            error = status.reasonPhrase,
            message = message ?: ex.message ?: "An unexpected error occurred",
            type = ex::class.simpleName ?: "UnknownError"
        )
    }
}

data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
    val type: String,
    val timestamp: Long = System.currentTimeMillis()
)

