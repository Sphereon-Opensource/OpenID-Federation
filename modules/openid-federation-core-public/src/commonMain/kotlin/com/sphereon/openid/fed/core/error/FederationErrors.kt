package com.sphereon.openid.fed.core.error

import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.error.IdkErrorType
import io.ktor.http.*

/**
 * Base sealed interface for all OpenID Federation errors.
 *
 * This follows the IDK pattern by implementing IdkErrorType, which allows
 * federation errors to be used with IdkResult for type-safe error handling.
 *
 * All federation errors have:
 * - A unique error code (for API responses and logging)
 * - An HTTP status code (for REST API responses)
 * - A message structure (for human-readable descriptions)
 */
sealed interface FederationError : IdkErrorType {
    /**
     * The OAuth2/OpenID Connect error code (e.g., "invalid_request", "invalid_client")
     */
    val errorCode: String

    /**
     * The HTTP status code for REST API responses
     */
    val httpStatus: HttpStatusCode

    /**
     * Helper property to get the HTTP status as an integer
     */
    val httpStatusValue: Int get() = httpStatus.value

    // IdkErrorType defaults
    override val severity: IdkError.Severity get() = IdkError.Severity.ERROR
    override val exception: Throwable? get() = null
    override val causes: List<IdkErrorType> get() = emptyList()
    override val meta: Map<String, Any?> get() = emptyMap()
}

// =============================================================================
// Entity Configuration Errors
// =============================================================================

/**
 * The requested entity was not found in the federation
 */
data class EntityNotFoundError(
    val entityId: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.NotFound
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.entity-not-found",
        i18nParams = mapOf("entityId" to entityId),
        defaultMessage = "Entity not found: $entityId"
    )

    companion object {
        const val ERROR_CODE = "entity_not_found"
    }
}

/**
 * The entity configuration is invalid or malformed
 *
 * @param entityId The entity identifier with invalid configuration
 * @param reason Human-readable description of the validation failure
 * @param exception Optional underlying exception
 * @param underlyingCauses List of underlying validation errors
 */
data class InvalidEntityConfigurationError(
    val entityId: String,
    val reason: String,
    override val exception: Throwable? = null,
    private val underlyingCauses: List<FederationError> = emptyList()
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.BadRequest
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.invalid-entity-configuration",
        i18nParams = mapOf("entityId" to entityId, "reason" to reason),
        defaultMessage = "Invalid entity configuration for $entityId: $reason"
    )
    override val causes: List<IdkErrorType> get() = underlyingCauses

    companion object {
        const val ERROR_CODE = "invalid_entity_configuration"
    }
}

// =============================================================================
// Trust Chain Errors
// =============================================================================

/**
 * Trust chain validation failed
 *
 * @param entityId The entity identifier that failed validation
 * @param reason Human-readable description of the failure
 * @param exception Optional underlying exception
 * @param underlyingCauses List of underlying errors that caused this failure (e.g., multiple validation failures)
 */
data class TrustChainValidationFailedError(
    val entityId: String,
    val reason: String,
    override val exception: Throwable? = null,
    private val underlyingCauses: List<FederationError> = emptyList()
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.BadRequest
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.invalid-trust-chain",
        i18nParams = mapOf("entityId" to entityId, "reason" to reason),
        defaultMessage = "Trust chain validation failed for $entityId: $reason"
    )
    override val causes: List<IdkErrorType> get() = underlyingCauses

    companion object {
        const val ERROR_CODE = "invalid_trust_chain"
    }
}

/**
 * No valid trust chain could be found from entity to trust anchor
 *
 * @param entityId The entity identifier that could not be resolved
 * @param trustAnchors The trust anchors that were tried
 * @param exception Optional underlying exception
 * @param resolutionAttempts List of errors from resolution attempts to each trust anchor
 */
data class NoTrustChainFoundError(
    val entityId: String,
    val trustAnchors: List<String> = emptyList(),
    override val exception: Throwable? = null,
    private val resolutionAttempts: List<FederationError> = emptyList()
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.NotFound
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.no-trust-chain",
        i18nParams = mapOf("entityId" to entityId, "trustAnchors" to trustAnchors.joinToString(", ")),
        defaultMessage = "No valid trust chain found from $entityId to any trust anchor"
    )
    override val causes: List<IdkErrorType> get() = resolutionAttempts

    companion object {
        const val ERROR_CODE = "no_trust_chain"
    }
}

/**
 * The specified trust anchor is invalid or not trusted
 */
data class InvalidTrustAnchorError(
    val trustAnchorId: String,
    val reason: String? = null,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.NotFound
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.invalid-trust-anchor",
        i18nParams = mapOf("trustAnchorId" to trustAnchorId, "reason" to (reason ?: "")),
        defaultMessage = "Invalid trust anchor: $trustAnchorId${reason?.let { " - $it" } ?: ""}"
    )

    companion object {
        const val ERROR_CODE = "invalid_trust_anchor"
    }
}

// =============================================================================
// Trust Mark Errors
// =============================================================================

/**
 * Trust mark validation failed
 *
 * @param trustMarkId The trust mark identifier that failed validation
 * @param reason Human-readable description of the failure
 * @param exception Optional underlying exception
 * @param underlyingCauses List of underlying validation errors
 */
data class TrustMarkInvalidError(
    val trustMarkId: String,
    val reason: String,
    override val exception: Throwable? = null,
    private val underlyingCauses: List<FederationError> = emptyList()
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.BadRequest
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.invalid-trust-mark",
        i18nParams = mapOf("trustMarkId" to trustMarkId, "reason" to reason),
        defaultMessage = "Invalid trust mark $trustMarkId: $reason"
    )
    override val causes: List<IdkErrorType> get() = underlyingCauses

    companion object {
        const val ERROR_CODE = "invalid_trust_mark"
    }
}

/**
 * Trust mark has expired
 */
data class TrustMarkExpiredError(
    val trustMarkId: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.BadRequest
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.trust-mark-expired",
        i18nParams = mapOf("trustMarkId" to trustMarkId),
        defaultMessage = "Trust mark expired: $trustMarkId"
    )

    companion object {
        const val ERROR_CODE = "trust_mark_expired"
    }
}

/**
 * Received trust mark not found
 */
data class ReceivedTrustMarkNotFoundError(
    val trustMarkId: String,
    val accountId: String? = null,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.NotFound
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.received-trust-mark-not-found",
        i18nParams = mapOf("trustMarkId" to trustMarkId, "accountId" to (accountId ?: "")),
        defaultMessage = "Received trust mark not found: $trustMarkId${accountId?.let { " for account $it" } ?: ""}"
    )

    companion object {
        const val ERROR_CODE = "received_trust_mark_not_found"
    }
}

/**
 * Trust mark not found
 */
data class TrustMarkNotFoundError(
    val trustMarkId: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.NotFound
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.trust-mark-not-found",
        i18nParams = mapOf("trustMarkId" to trustMarkId),
        defaultMessage = "Trust mark not found: $trustMarkId"
    )

    companion object {
        const val ERROR_CODE = "trust_mark_not_found"
    }
}

/**
 * Trust mark type not found
 */
data class TrustMarkTypeNotFoundError(
    val trustMarkTypeId: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.NotFound
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.trust-mark-type-not-found",
        i18nParams = mapOf("trustMarkTypeId" to trustMarkTypeId),
        defaultMessage = "Trust mark type not found: $trustMarkTypeId"
    )

    companion object {
        const val ERROR_CODE = "trust_mark_type_not_found"
    }
}

/**
 * Trust mark issuer is not authorized
 */
data class TrustMarkIssuerNotAuthorizedError(
    val trustMarkId: String,
    val issuerId: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.Forbidden
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.trust-mark-issuer-not-authorized",
        i18nParams = mapOf("trustMarkId" to trustMarkId, "issuerId" to issuerId),
        defaultMessage = "Trust mark issuer $issuerId is not authorized to issue $trustMarkId"
    )

    companion object {
        const val ERROR_CODE = "trust_mark_issuer_not_authorized"
    }
}

// =============================================================================
// Signature and JWT Errors
// =============================================================================

/**
 * Signature verification failed
 */
data class SignatureVerificationFailedError(
    val reason: String,
    val keyId: String? = null,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.Unauthorized
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.signature-verification-failed",
        i18nParams = mapOf("reason" to reason, "keyId" to (keyId ?: "")),
        defaultMessage = "Signature verification failed: $reason${keyId?.let { " (kid: $it)" } ?: ""}"
    )

    companion object {
        const val ERROR_CODE = "signature_verification_failed"
    }
}

/**
 * JWT creation failed
 */
data class JwtCreationFailedError(
    val reason: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.InternalServerError
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.jwt-creation-failed",
        i18nParams = mapOf("reason" to reason),
        defaultMessage = "Failed to create JWT: $reason"
    )

    companion object {
        const val ERROR_CODE = "jwt_creation_failed"
    }
}

/**
 * JWT parsing or validation failed
 *
 * @param reason Human-readable description of the failure
 * @param exception Optional underlying exception
 * @param underlyingCauses List of underlying validation errors (e.g., multiple claim validation failures)
 */
data class JwtValidationFailedError(
    val reason: String,
    override val exception: Throwable? = null,
    private val underlyingCauses: List<FederationError> = emptyList()
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.BadRequest
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.jwt-validation-failed",
        i18nParams = mapOf("reason" to reason),
        defaultMessage = "JWT validation failed: $reason"
    )
    override val causes: List<IdkErrorType> get() = underlyingCauses

    companion object {
        const val ERROR_CODE = "jwt_validation_failed"
    }
}

// =============================================================================
// Key Management Errors
// =============================================================================

/**
 * Invalid KMS provider configuration
 */
data class InvalidKmsProviderError(
    val provider: String,
    val reason: String? = null,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.InternalServerError
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.invalid-kms-provider",
        i18nParams = mapOf("provider" to provider, "reason" to (reason ?: "")),
        defaultMessage = "Invalid KMS provider: $provider${reason?.let { " - $it" } ?: ""}"
    )

    companion object {
        const val ERROR_CODE = "invalid_kms_provider"
    }
}

/**
 * The signing key was not found or is not configured
 */
data class SigningKeyNotFoundError(
    val keyAlias: String? = null,
    val tenantId: String? = null,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.InternalServerError
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.signing-key-not-found",
        i18nParams = mapOf("keyAlias" to (keyAlias ?: ""), "tenantId" to (tenantId ?: "")),
        defaultMessage = "Signing key not found${keyAlias?.let { ": $it" } ?: ""}${tenantId?.let { " for tenant $it" } ?: ""}"
    )

    companion object {
        const val ERROR_CODE = "signing_key_not_found"
    }
}

/**
 * Key not found in the key store
 */
data class KeyNotFoundError(
    val keyId: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.NotFound
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.key-not-found",
        i18nParams = mapOf("keyId" to keyId),
        defaultMessage = "Key not found: $keyId"
    )

    companion object {
        const val ERROR_CODE = "key_not_found"
    }
}

/**
 * No signing key is configured for the tenant
 */
data class NoSigningKeyConfiguredError(
    val tenantId: String? = null,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.InternalServerError
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.no-signing-key-configured",
        i18nParams = mapOf("tenantId" to (tenantId ?: "")),
        defaultMessage = "No signing key configured${tenantId?.let { " for tenant $it" } ?: ""}"
    )

    companion object {
        const val ERROR_CODE = "no_signing_key_configured"
    }
}

/**
 * Key resolution failed
 */
data class KeyResolutionFailedError(
    val identifier: String,
    val reason: String? = null,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.InternalServerError
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.key-resolution-failed",
        i18nParams = mapOf("identifier" to identifier, "reason" to (reason ?: "")),
        defaultMessage = "Failed to resolve key: $identifier${reason?.let { " - $it" } ?: ""}"
    )

    companion object {
        const val ERROR_CODE = "key_resolution_failed"
    }
}

// =============================================================================
// Subordinate Errors
// =============================================================================

/**
 * Subordinate entity not found
 */
data class SubordinateNotFoundError(
    val subordinateId: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.NotFound
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.subordinate-not-found",
        i18nParams = mapOf("subordinateId" to subordinateId),
        defaultMessage = "Subordinate not found: $subordinateId"
    )

    companion object {
        const val ERROR_CODE = "subordinate_not_found"
    }
}

/**
 * Subordinate statement is invalid
 */
data class InvalidSubordinateStatementError(
    val subordinateId: String,
    val reason: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.BadRequest
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.invalid-subordinate-statement",
        i18nParams = mapOf("subordinateId" to subordinateId, "reason" to reason),
        defaultMessage = "Invalid subordinate statement for $subordinateId: $reason"
    )

    companion object {
        const val ERROR_CODE = "invalid_subordinate_statement"
    }
}

// =============================================================================
// HTTP/Network Errors
// =============================================================================

/**
 * HTTP request to federation endpoint failed
 */
data class FederationHttpError(
    val url: String,
    val statusCode: Int,
    val reason: String? = null,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.BadGateway
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.federation-http-error",
        i18nParams = mapOf("url" to url, "statusCode" to statusCode.toString(), "reason" to (reason ?: "")),
        defaultMessage = "HTTP request to $url failed with status $statusCode${reason?.let { ": $it" } ?: ""}"
    )

    companion object {
        const val ERROR_CODE = "federation_http_error"
    }
}

/**
 * Network error when communicating with federation endpoint
 */
data class FederationNetworkError(
    val url: String,
    val reason: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.ServiceUnavailable
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.federation-network-error",
        i18nParams = mapOf("url" to url, "reason" to reason),
        defaultMessage = "Network error accessing $url: $reason"
    )

    companion object {
        const val ERROR_CODE = "federation_network_error"
    }
}

// =============================================================================
// Request Validation Errors
// =============================================================================

/**
 * The request is invalid or malformed
 */
data class InvalidRequestError(
    val reason: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.BadRequest
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.invalid-request",
        i18nParams = mapOf("reason" to reason),
        defaultMessage = "Invalid request: $reason"
    )

    companion object {
        const val ERROR_CODE = "invalid_request"
    }
}

/**
 * A required parameter is missing
 */
data class MissingParameterError(
    val parameterName: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.BadRequest
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.missing-parameter",
        i18nParams = mapOf("parameterName" to parameterName),
        defaultMessage = "Missing required parameter: $parameterName"
    )

    companion object {
        const val ERROR_CODE = "missing_parameter"
    }
}

/**
 * The parameter value is not supported
 */
data class UnsupportedParameterError(
    val parameterName: String,
    val parameterValue: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.BadRequest
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.unsupported-parameter",
        i18nParams = mapOf("parameterName" to parameterName, "parameterValue" to parameterValue),
        defaultMessage = "Unsupported value for parameter $parameterName: $parameterValue"
    )

    companion object {
        const val ERROR_CODE = "unsupported_parameter"
    }
}

// =============================================================================
// Account/Tenant Errors
// =============================================================================

/**
 * Account not found
 */
data class AccountNotFoundError(
    val accountId: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.NotFound
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.account-not-found",
        i18nParams = mapOf("accountId" to accountId),
        defaultMessage = "Account not found: $accountId"
    )

    companion object {
        const val ERROR_CODE = "account_not_found"
    }
}

/**
 * Account already exists
 */
data class AccountAlreadyExistsError(
    val identifier: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.Conflict
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.account-already-exists",
        i18nParams = mapOf("identifier" to identifier),
        defaultMessage = "Account already exists with identifier: $identifier"
    )

    companion object {
        const val ERROR_CODE = "account_already_exists"
    }
}

// =============================================================================
// Critical Claim Errors
// =============================================================================

/**
 * Critical claim already exists
 */
data class CriticalClaimAlreadyExistsError(
    val accountId: String,
    val claim: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.Conflict
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.critical-claim-already-exists",
        i18nParams = mapOf("accountId" to accountId, "claim" to claim),
        defaultMessage = "Critical claim '$claim' already exists for account: $accountId"
    )

    companion object {
        const val ERROR_CODE = "critical_claim_already_exists"
    }
}

/**
 * Critical claim not found
 */
data class CriticalClaimNotFoundError(
    val claimId: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.NotFound
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.critical-claim-not-found",
        i18nParams = mapOf("claimId" to claimId),
        defaultMessage = "Critical claim not found: $claimId"
    )

    companion object {
        const val ERROR_CODE = "critical_claim_not_found"
    }
}

// =============================================================================
// Metadata Errors
// =============================================================================

/**
 * Metadata already exists
 */
data class MetadataAlreadyExistsError(
    val accountId: String,
    val key: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.Conflict
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.metadata-already-exists",
        i18nParams = mapOf("accountId" to accountId, "key" to key),
        defaultMessage = "Metadata with key '$key' already exists for account: $accountId"
    )

    companion object {
        const val ERROR_CODE = "metadata_already_exists"
    }
}

/**
 * Metadata not found
 */
data class MetadataNotFoundError(
    val metadataId: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.NotFound
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.metadata-not-found",
        i18nParams = mapOf("metadataId" to metadataId),
        defaultMessage = "Metadata not found: $metadataId"
    )

    companion object {
        const val ERROR_CODE = "metadata_not_found"
    }
}

// =============================================================================
// Metadata Policy Errors
// =============================================================================

/**
 * Metadata policy already exists
 */
data class MetadataPolicyAlreadyExistsError(
    val accountId: String,
    val key: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.Conflict
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.metadata-policy-already-exists",
        i18nParams = mapOf("accountId" to accountId, "key" to key),
        defaultMessage = "Metadata policy with key '$key' already exists for account: $accountId"
    )

    companion object {
        const val ERROR_CODE = "metadata_policy_already_exists"
    }
}

/**
 * Metadata policy not found
 */
data class MetadataPolicyNotFoundError(
    val policyId: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.NotFound
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.metadata-policy-not-found",
        i18nParams = mapOf("policyId" to policyId),
        defaultMessage = "Metadata policy not found: $policyId"
    )

    companion object {
        const val ERROR_CODE = "metadata_policy_not_found"
    }
}

/**
 * Metadata policy processing failed
 */
data class MetadataPolicyError(
    val policyPath: String,
    val reason: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.BadRequest
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.invalid-metadata-policy",
        i18nParams = mapOf("policyPath" to policyPath, "reason" to reason),
        defaultMessage = "Metadata policy error at $policyPath: $reason"
    )

    companion object {
        const val ERROR_CODE = "invalid_metadata_policy"
    }
}

// =============================================================================
// Server Errors
// =============================================================================

/**
 * An unexpected server error occurred
 *
 * @param reason Human-readable description of the error
 * @param causeDescription Optional string description of the cause (for messages)
 * @param exception Optional underlying exception
 * @param underlyingCause Optional underlying federation error that caused this
 */
data class ServerError(
    val reason: String,
    val causeDescription: String? = null,
    override val exception: Throwable? = null,
    private val underlyingCause: FederationError? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.InternalServerError
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.server-error",
        i18nParams = mapOf("reason" to reason, "cause" to (causeDescription ?: "")),
        defaultMessage = "Server error: $reason${causeDescription?.let { " (caused by: $it)" } ?: ""}"
    )
    override val causes: List<IdkErrorType> get() = listOfNotNull(underlyingCause)

    companion object {
        const val ERROR_CODE = "server_error"
    }
}

/**
 * Error that preserves the original HTTP status code when round-tripping through IdkError.
 * Used by toFederationResult() to avoid losing status information when converting IdkError back to FederationError.
 */
data class PreservedError(
    override val httpStatusValue: Int,
    val reason: String,
    override val errorCode: String,
    val causeDescription: String? = null,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = errorCode
    override val httpStatus: HttpStatusCode = HttpStatusCode.fromValue(httpStatusValue)
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.preserved-error",
        i18nParams = mapOf("reason" to reason),
        defaultMessage = reason
    )
}

/**
 * The service is temporarily unavailable
 */
data class ServiceUnavailableError(
    val reason: String? = null,
    val retryAfterSeconds: Int? = null,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.ServiceUnavailable
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.temporarily-unavailable",
        i18nParams = mapOf("reason" to (reason ?: ""), "retryAfterSeconds" to (retryAfterSeconds?.toString() ?: "")),
        defaultMessage = "Service temporarily unavailable${reason?.let { ": $it" } ?: ""}"
    )

    companion object {
        const val ERROR_CODE = "temporarily_unavailable"
    }
}

// =============================================================================
// Authorization Errors
// =============================================================================

/**
 * The client is not authorized
 */
data class UnauthorizedError(
    val reason: String? = null,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.Unauthorized
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.unauthorized",
        i18nParams = mapOf("reason" to (reason ?: "")),
        defaultMessage = "Unauthorized${reason?.let { ": $it" } ?: ""}"
    )

    companion object {
        const val ERROR_CODE = "invalid_client"
    }
}

/**
 * Access to the resource is forbidden
 */
data class ForbiddenError(
    val reason: String? = null,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.Forbidden
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.forbidden",
        i18nParams = mapOf("reason" to (reason ?: "")),
        defaultMessage = "Forbidden${reason?.let { ": $it" } ?: ""}"
    )

    companion object {
        const val ERROR_CODE = "access_denied"
    }
}

// =============================================================================
// Resolution Errors
// =============================================================================

/**
 * Entity resolution failed (for external entities via OIDF protocol)
 *
 * @param entityId The entity identifier that could not be resolved
 * @param reason Human-readable description of the failure
 * @param exception Optional underlying exception
 * @param underlyingCause Optional underlying federation error (e.g., network or validation error)
 */
data class EntityResolutionFailedError(
    val entityId: String,
    val reason: String? = null,
    override val exception: Throwable? = null,
    private val underlyingCause: FederationError? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.NotFound
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.entity-resolution-failed",
        i18nParams = mapOf("entityId" to entityId, "reason" to (reason ?: "")),
        defaultMessage = "Failed to resolve entity $entityId${reason?.let { ": $it" } ?: ""}"
    )
    override val causes: List<IdkErrorType> get() = listOfNotNull(underlyingCause)

    companion object {
        const val ERROR_CODE = "entity_resolution_failed"
    }
}

/**
 * X.509 certificate verification failed
 */
data class X509VerificationFailedError(
    val reason: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.BadRequest
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.x509-verification-failed",
        i18nParams = mapOf("reason" to reason),
        defaultMessage = "X.509 verification failed: $reason"
    )

    companion object {
        const val ERROR_CODE = "x509_verification_failed"
    }
}
