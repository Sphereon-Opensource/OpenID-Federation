package com.sphereon.openid.fed.core.security

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.conf.OpaqueSecretResolver
import com.sphereon.core.api.error.IdkError

/**
 * Standalone [OpaqueSecretResolver] for open-source deployments.
 *
 * Resolves a secret id by looking up environment variables, in order:
 * 1. `OIDF_SECRET_<NORMALIZED_ID>`
 * 2. Direct env var equal to the secret id (uppercase with dots → underscores)
 *
 * This is **not** a vault. Host platforms (EDK/VDX) should bind a real
 * [OpaqueSecretResolver] / vault-backed implementation instead.
 *
 * @param envLookup Platform env access
 */
class EnvOpaqueSecretResolver(
    private val envLookup: (String) -> String?,
) : OpaqueSecretResolver {

    override suspend fun resolve(secretId: String): IdkResult<String, IdkError> {
        if (secretId.isBlank()) {
            return IdkResult.err(secretNotFound(secretId, "blank secret id"))
        }
        val normalized = secretId.replace(".", "_").replace("-", "_").uppercase()
        val candidates = listOf(
            "OIDF_SECRET_$normalized",
            normalized,
            secretId,
        )
        for (key in candidates) {
            envLookup(key)?.takeIf { it.isNotEmpty() }?.let {
                return IdkResult.ok(it)
            }
        }
        return IdkResult.err(secretNotFound(secretId, "No env mapping for secret id"))
    }

    private fun secretNotFound(secretId: String, reason: String): IdkError =
        IdkError(
            code = "secret_not_found",
            message = IdkError.Message(
                i18nKey = "com.sphereon.openid.fed.error.secret-not-found",
                i18nParams = mapOf("secretId" to secretId, "reason" to reason),
                defaultMessage = "Secret not found: $secretId ($reason)",
            ),
            severity = IdkError.Severity.ERROR,
            meta = mapOf("secretId" to secretId),
        )
}

/**
 * Optional companion key for a cleartext property: if set, the value is an opaque secret id.
 * Example: `oidf.datasource.password.secret.id=db-password` resolved via [OpaqueSecretResolver].
 */
fun secretIdKeyFor(propertyKey: String): String = "$propertyKey.secret.id"
