package com.sphereon.openid.fed.core.error

import com.sphereon.core.api.Err
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.error.IdkError

/**
 * Type alias for federation operation results.
 *
 * This is the standard return type for all federation operations,
 * providing type-safe error handling using the IDK's IdkResult pattern.
 *
 * Example usage:
 * ```kotlin
 * suspend fun getEntityConfiguration(entityId: String): FederationResult<EntityConfiguration> {
 *     return try {
 *         val config = fetchEntityConfiguration(entityId)
 *         Ok(config)
 *     } catch (e: Exception) {
 *         Err(EntityNotFoundError(entityId))
 *     }
 * }
 * ```
 */
typealias FederationResult<T> = IdkResult<T, FederationError>

/**
 * Extension function to convert a FederationError to a typed Err result.
 */
fun <T> FederationError.toErr(): FederationResult<T> = Err(this).asResult()

/**
 * Extension function to wrap a value in an Ok result.
 */
fun <T> T.toOk(): FederationResult<T> = Ok(this).asResult()

/**
 * Extension function to map a FederationResult's error to a different FederationError type.
 */
inline fun <T, E : FederationError> FederationResult<T>.mapFederationError(
    transform: (FederationError) -> E
): IdkResult<T, E> = this.mapError(transform)

/**
 * Extension function to recover from a specific error type.
 */
inline fun <T, reified E : FederationError> FederationResult<T>.recoverIf(
    recovery: (E) -> T
): FederationResult<T> = when {
    this.isErr && this.error is E -> Ok(recovery(this.error as E)).asResult()
    else -> this
}

/**
 * Extension function to log errors without changing the result.
 */
inline fun <T> FederationResult<T>.onFederationError(
    action: (FederationError) -> Unit
): FederationResult<T> {
    if (this.isErr) {
        action(this.error)
    }
    return this
}

/**
 * Extension function to log success without changing the result.
 */
inline fun <T> FederationResult<T>.onFederationSuccess(
    action: (T) -> Unit
): FederationResult<T> {
    if (this.isOk) {
        action(this.value)
    }
    return this
}

/**
 * Convert an IdkResult with any IdkErrorType to a FederationResult.
 *
 * This is useful when wrapping IDK operations and converting their errors
 * to federation-specific errors.
 */
inline fun <T, E : com.sphereon.core.api.error.IdkErrorType> IdkResult<T, E>.toFederationResult(
    errorMapper: (E) -> FederationError
): FederationResult<T> = this.mapError(errorMapper)

/**
 * Run a block that may throw an exception, catching it and converting to a FederationResult.
 */
inline fun <T> runFederationCatching(
    errorMapper: (Exception) -> FederationError,
    block: () -> T
): FederationResult<T> {
    return try {
        Ok(block()).asResult()
    } catch (e: Exception) {
        Err(errorMapper(e)).asResult()
    }
}

/**
 * Run a suspend block that may throw an exception, catching it and converting to a FederationResult.
 */
suspend inline fun <T> runFederationCatchingSuspend(
    errorMapper: (Exception) -> FederationError,
    block: suspend () -> T
): FederationResult<T> {
    return try {
        Ok(block()).asResult()
    } catch (e: Exception) {
        Err(errorMapper(e)).asResult()
    }
}

/**
 * Get the value or throw an exception wrapping the error.
 *
 * This is useful in legacy code that still uses exceptions,
 * allowing gradual migration to FederationResult.
 */
fun <T> FederationResult<T>.getOrThrow(): T {
    return when {
        this.isOk -> this.value
        else -> throw FederationErrorException(this.error)
    }
}

/**
 * Get the value or return a default if this is an error.
 */
inline fun <T> FederationResult<T>.getOrElse(default: (FederationError) -> T): T {
    return when {
        this.isOk -> this.value
        else -> default(this.error)
    }
}

/**
 * Get the value or return null if this is an error.
 */
fun <T> FederationResult<T>.getOrNull(): T? {
    return when {
        this.isOk -> this.value
        else -> null
    }
}

/**
 * Chain a FederationResult with another operation that returns FederationResult.
 * If this result is Ok, apply the transform. If this result is Err, propagate the error.
 */
inline fun <T, R> FederationResult<T>.andThen(
    transform: (T) -> FederationResult<R>
): FederationResult<R> {
    return when {
        this.isOk -> transform(this.value)
        else -> Err(this.error).asResult()
    }
}

/**
 * Suspend version of andThen for async operations.
 */
suspend inline fun <T, R> FederationResult<T>.andThenSuspend(
    transform: suspend (T) -> FederationResult<R>
): FederationResult<R> {
    return when {
        this.isOk -> transform(this.value)
        else -> Err(this.error).asResult()
    }
}

/**
 * Convert a FederationError to an IdkError.
 *
 * Since IdkError is a class and FederationError is a sealed interface (both implementing IdkErrorType),
 * this conversion bridges the two for use with ServiceCommand<TInput, TOutput> which is fixed to IdkError.
 */
fun FederationError.toIdkError(): IdkError = IdkError(
    code = code,
    message = message,
    severity = severity,
    causes = causes,
    meta = meta + mapOf("httpStatus" to httpStatusValue, "errorCode" to errorCode),
    exception = exception
)

/**
 * Convert an IdkResult<T, FederationError> to an IdkResult<T, IdkError>.
 *
 * Used when service command implementations need to return IdkResult<T, IdkError>
 * (as required by ServiceCommand/TypedServiceCommandAdapter) but internally work with FederationError.
 */
fun <T> IdkResult<T, FederationError>.toIdkErrorResult(): IdkResult<T, IdkError> =
    this.mapError { it.toIdkError() }

/**
 * Convert an IdkResult<T, IdkError> back to a FederationResult<T>.
 *
 * Used by ServiceFacade implementations to provide FederationResult<T> from ServiceCommand results.
 * If the IdkError was originally a FederationError (preserved in meta), extracts the httpStatus.
 * Otherwise wraps in a generic ServerError.
 */
fun <T> IdkResult<T, IdkError>.toFederationResult(): FederationResult<T> =
    this.mapError { idkError ->
        ServerError(
            reason = idkError.message.defaultMessage,
            causeDescription = idkError.code,
            exception = idkError.exception
        )
    }

/**
 * Create an IdkResult.err with a FederationError automatically converted to IdkError.
 */
fun <T> federationErr(error: FederationError): IdkResult<T, IdkError> =
    IdkResult.err(error.toIdkError())

/**
 * Exception wrapper for FederationError.
 * Used by getOrThrow() to convert errors to exceptions for legacy code.
 */
class FederationErrorException(
    val federationError: FederationError
) : Exception(federationError.message.defaultMessage) {
    override val cause: Throwable? get() = federationError.exception
}
