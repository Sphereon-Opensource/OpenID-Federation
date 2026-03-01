package com.sphereon.openid.fed.core.error

import com.sphereon.core.api.error.IdkError
import io.ktor.http.*

/**
 * Tenant not found error.
 *
 * Returned when a tenant cannot be resolved from a request
 * (e.g., unknown username in path, invalid JWT tenant claim, etc.)
 */
data class TenantNotFoundError(
    val tenantId: String,
    override val exception: Throwable? = null
) : FederationError {
    override val code: String = ERROR_CODE
    override val errorCode: String = ERROR_CODE
    override val httpStatus: HttpStatusCode = HttpStatusCode.NotFound
    override val message: IdkError.Message = IdkError.Message(
        i18nKey = "com.sphereon.openid.fed.error.tenant-not-found",
        i18nParams = mapOf("tenantId" to tenantId),
        defaultMessage = "Tenant not found: $tenantId"
    )

    companion object {
        const val ERROR_CODE = "tenant_not_found"
    }
}
