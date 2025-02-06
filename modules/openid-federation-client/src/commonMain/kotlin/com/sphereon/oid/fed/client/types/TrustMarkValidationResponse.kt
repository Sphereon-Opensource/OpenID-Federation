package com.sphereon.oid.fed.client.types

/**
 * Response class for Trust Mark validation results
 *
 * @property isValid Whether the Trust Mark is valid
 * @property errorMessage Optional error message if validation failed
 */
data class TrustMarkValidationResponse(
    val isValid: Boolean,
    val errorMessage: String? = null
)