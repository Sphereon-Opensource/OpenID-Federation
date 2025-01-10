package com.sphereon.oid.fed.server.admin.handlers

import com.sphereon.oid.fed.common.exceptions.ApplicationException
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException

@ControllerAdvice
class ExceptionHandler {
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
            message = ex.message ?: "An unexpected error occurred"
        )

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
            message = ex.message ?: "An unexpected error occurred"
        )

        return ResponseEntity.status(status).body(errorResponse)
    }
}

data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
