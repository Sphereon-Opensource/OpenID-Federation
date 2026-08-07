package com.sphereon.openid.fed.openapi.models

/**
 * Source-compatible aliases for the historical OpenAPI/model names.
 *
 * Canonical types are [TenantJwk] / [TenantJwksResponse]. The JSON wire shape is unchanged
 * (property names including `accountId`, `kmsKeyRef`, `jwks`, …).
 *
 * OpenAPI still publishes `AccountJwk` / `AccountJwksResponse` as `$ref` aliases of the
 * Tenant* schemas so external tools that resolve the old component names keep working.
 */
@Deprecated(
    message = "Use TenantJwk — same JSON shape; AccountJwk is a historical name",
    replaceWith = ReplaceWith("TenantJwk", "com.sphereon.openid.fed.openapi.models.TenantJwk")
)
typealias AccountJwk = TenantJwk

@Deprecated(
    message = "Use TenantJwksResponse — same JSON shape; AccountJwksResponse is a historical name",
    replaceWith = ReplaceWith(
        "TenantJwksResponse",
        "com.sphereon.openid.fed.openapi.models.TenantJwksResponse"
    )
)
typealias AccountJwksResponse = TenantJwksResponse
