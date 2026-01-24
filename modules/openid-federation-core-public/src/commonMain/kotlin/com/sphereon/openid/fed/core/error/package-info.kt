/**
 * Federation Error Types Package.
 *
 * This package contains the error type hierarchy for OpenID Federation operations.
 * All errors implement [FederationError], which extends IDK's [IdkErrorType].
 *
 * ## Error Categories
 *
 * ### Entity Errors
 * - [EntityNotFoundError] - Entity not found in federation
 * - [InvalidEntityConfigurationError] - Entity configuration is malformed
 * - [EntityResolutionFailedError] - Failed to resolve external entity
 *
 * ### Trust Chain Errors
 * - [TrustChainValidationFailedError] - Trust chain validation failed
 * - [NoTrustChainFoundError] - No valid trust chain to trust anchor
 * - [InvalidTrustAnchorError] - Trust anchor is invalid
 *
 * ### Trust Mark Errors
 * - [TrustMarkInvalidError] - Trust mark validation failed
 * - [TrustMarkExpiredError] - Trust mark has expired
 * - [TrustMarkIssuerNotAuthorizedError] - Issuer not authorized
 *
 * ### JWT/Signature Errors
 * - [SignatureVerificationFailedError] - Signature verification failed
 * - [JwtCreationFailedError] - JWT creation failed
 * - [JwtValidationFailedError] - JWT validation failed
 *
 * ### Key Management Errors
 * - [SigningKeyNotFoundError] - Signing key not found
 * - [NoSigningKeyConfiguredError] - No signing key configured
 * - [KeyResolutionFailedError] - Key resolution failed
 *
 * ### HTTP/Network Errors
 * - [FederationHttpError] - HTTP request failed
 * - [FederationNetworkError] - Network error
 *
 * ### Request Validation Errors
 * - [InvalidRequestError] - Request is invalid
 * - [MissingParameterError] - Required parameter missing
 * - [UnsupportedParameterError] - Parameter value not supported
 *
 * ### Account Errors
 * - [AccountNotFoundError] - Account not found
 * - [AccountAlreadyExistsError] - Account already exists
 *
 * ### Server Errors
 * - [ServerError] - Unexpected server error
 * - [ServiceUnavailableError] - Service temporarily unavailable
 *
 * ## Usage
 *
 * Use [FederationResult] type alias for return types:
 *
 * ```kotlin
 * suspend fun getEntity(id: String): FederationResult<Entity> {
 *     val entity = repository.findById(id)
 *         ?: return EntityNotFoundError(id).toErr()
 *     return entity.toOk()
 * }
 * ```
 *
 * Handle errors with pattern matching:
 *
 * ```kotlin
 * when (val result = service.getEntity(id)) {
 *     is Ok -> println("Found: ${result.value}")
 *     is Err -> when (val error = result.error) {
 *         is EntityNotFoundError -> println("Not found: ${error.entityId}")
 *         is InvalidEntityConfigurationError -> println("Invalid: ${error.reason}")
 *         else -> println("Error: ${error.message.defaultMessage}")
 *     }
 * }
 * ```
 *
 * Convert between exceptions and results:
 *
 * ```kotlin
 * // Catch exceptions and convert to FederationResult
 * val result = runFederationCatching({ e ->
 *     ServerError(e.message ?: "Unknown error", exception = e)
 * }) {
 *     riskyOperation()
 * }
 * ```
 */
package com.sphereon.openid.fed.core.error
