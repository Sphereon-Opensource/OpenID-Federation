package com.sphereon.openid.fed.core.tenant

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extract platform tenant id from JWT/OIDC claims (IDK-aligned claim names).
 *
 * ## Boundary
 * Mirrors IDK [com.sphereon.core.defaults.context.OidcTenantResolver] claim order so
 * OIDFed session resolution matches host OIDC/JWT conventions.
 *
 * Claim names checked in order:
 * `tenant_id`, `tid`, `tenantId`, `org_id`, `organization_id`, `tenant`
 *
 * Only use claim maps from **validated** tokens (signature/iss/exp checked upstream).
 * Never treat caller-controlled headers as equivalent to these claims.
 */
object PlatformJwtTenantClaims {

    val TENANT_CLAIM_NAMES: List<String> = listOf(
        "tenant_id",
        "tid",
        "tenantId",
        "org_id",
        "organization_id",
        "tenant",
    )

    /**
     * @return trimmed non-blank tenant id, or null if none of the known claims are present
     */
    fun extractTenantId(claims: Map<String, JsonElement>): String? {
        for (name in TENANT_CLAIM_NAMES) {
            val value = claims[name]?.stringContent()?.trim()?.takeIf { it.isNotEmpty() }
            if (value != null) return value
        }
        return null
    }

    private fun JsonElement.stringContent(): String? =
        try {
            jsonPrimitive.contentOrNull
        } catch (_: Exception) {
            null
        }
}
