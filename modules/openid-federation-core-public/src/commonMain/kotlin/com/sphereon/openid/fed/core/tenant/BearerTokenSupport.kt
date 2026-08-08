package com.sphereon.openid.fed.core.tenant

import com.sphereon.core.defaults.context.JwtClaimsParser
import kotlinx.serialization.json.JsonElement

/**
 * Shared Bearer token helpers for PLATFORM tenant routing.
 *
 * ## Security boundary
 * [parseClaimsFromAuthorizationHeader] only **decodes** JWT payload claims (no signature check).
 * Use for tenant routing **only after** a validation gate has accepted the request
 * (IDK [JwtAuthentication] plugin, API gateway, or mesh JWT filter).
 *
 * Never treat decoded claims as proof of authenticity without that gate.
 */
object BearerTokenSupport {

    /**
     * Extract raw access token from an Authorization header value (`Bearer` or `DPoP`).
     */
    fun extractAccessToken(authorizationHeader: String?): String? {
        if (authorizationHeader.isNullOrBlank()) return null
        val trimmed = authorizationHeader.trim()
        val space = trimmed.indexOf(' ')
        if (space <= 0) return null
        val scheme = trimmed.substring(0, space)
        if (!scheme.equals("Bearer", ignoreCase = true) && !scheme.equals("DPoP", ignoreCase = true)) {
            return null
        }
        return trimmed.substring(space + 1).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Decode JWT payload claims from an Authorization header (unverified).
     */
    fun parseClaimsFromAuthorizationHeader(authorizationHeader: String?): Map<String, JsonElement>? {
        val token = extractAccessToken(authorizationHeader) ?: return null
        return JwtClaimsParser.parseClaimsOrNull(token)
    }

    /**
     * PLATFORM tenant id from Authorization header claims, if present.
     */
    fun platformTenantFromAuthorizationHeader(authorizationHeader: String?): String? {
        val claims = parseClaimsFromAuthorizationHeader(authorizationHeader) ?: return null
        return PlatformJwtTenantClaims.extractTenantId(claims)
    }
}
