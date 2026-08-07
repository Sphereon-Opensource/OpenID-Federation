package com.sphereon.openid.fed.core.config

/**
 * Property key constants for OpenID Federation configuration.
 *
 * All properties use the `oidf.` prefix following IDK naming conventions.
 * These keys are used for property resolution via IDK's ConfigService.
 */
object OidfConfigKeys {
    /** Root prefix for all OIDF configuration properties */
    const val OIDF_PREFIX = "oidf"

    // ========================================================================
    // Federation Core Settings (APP scope)
    // ========================================================================

    object Federation {
        const val PREFIX = "$OIDF_PREFIX.federation"

        /** Federation root entity identifier URL */
        const val ROOT_IDENTIFIER = "$PREFIX.root.identifier"

        /** Development mode flag */
        const val DEV_MODE = "$PREFIX.dev.mode"
    }

    // ========================================================================
    // Server Configuration (APP scope)
    // ========================================================================

    object Server {
        const val PREFIX = "$OIDF_PREFIX.server"

        object Admin {
            const val PREFIX = "${Server.PREFIX}.admin"

            /** Admin server port */
            const val PORT = "$PREFIX.port"

            /** Admin server bind host */
            const val HOST = "$PREFIX.host"
        }

        object Federation {
            const val PREFIX = "${Server.PREFIX}.federation"

            /** Federation server port */
            const val PORT = "$PREFIX.port"

            /** Federation server bind host */
            const val HOST = "$PREFIX.host"
        }
    }

    // ========================================================================
    // Datasource Configuration (APP scope)
    // ========================================================================

    object Datasource {
        const val PREFIX = "$OIDF_PREFIX.datasource"

        /** JDBC connection URL */
        const val URL = "$PREFIX.url"

        /** Database username */
        const val USER = "$PREFIX.user"

        /** Database password */
        const val PASSWORD = "$PREFIX.password"

        /** Database name (optional, for URL construction) */
        const val DB = "$PREFIX.db"
    }

    // ========================================================================
    // OAuth2 Configuration (APP scope)
    // ========================================================================

    object OAuth2 {
        const val PREFIX = "$OIDF_PREFIX.oauth2"

        /** OIDC issuer URI for JWT validation */
        const val ISSUER_URI = "$PREFIX.issuer.uri"

        /** Expected access-token audience (optional) */
        const val AUDIENCE = "$PREFIX.audience"

        /**
         * Install IDK JwtAuthentication plugin: `auto` | `true` | `false`
         * @see com.sphereon.openid.fed.core.config.OAuth2Config.jwtAuthEnabled
         */
        const val JWT_AUTH_ENABLED = "$PREFIX.jwt.auth.enabled"
    }

    // ========================================================================
    // Logger Configuration (APP scope)
    // ========================================================================

    object Logger {
        const val PREFIX = "$OIDF_PREFIX.logger"

        /** Log severity level */
        const val SEVERITY = "$PREFIX.severity"

        /** Log output format */
        const val OUTPUT = "$PREFIX.output"

        /** Include timestamps in logs */
        const val INCLUDE_TIMESTAMP = "$PREFIX.include.timestamp"
    }

    // ========================================================================
    // CORS Configuration (APP scope)
    // ========================================================================

    object Cors {
        const val PREFIX = "$OIDF_PREFIX.cors"

        /** Allowed origins (comma-separated) */
        const val ALLOWED_ORIGINS = "$PREFIX.allowed.origins"

        /** Allowed HTTP methods (comma-separated) */
        const val ALLOWED_METHODS = "$PREFIX.allowed.methods"

        /** Allowed headers (comma-separated) */
        const val ALLOWED_HEADERS = "$PREFIX.allowed.headers"

        /** CORS preflight cache max age in seconds */
        const val MAX_AGE = "$PREFIX.max.age"
    }

    // ========================================================================
    // Cache locality overrides (APP scope) — optional distributed deployment
    // ========================================================================

    object Cache {
        const val PREFIX = "$OIDF_PREFIX.cache"

        /**
         * Override IDK [com.sphereon.core.api.cache.CacheLocality] per namespace
         * (enum name, e.g. LOCAL_ONLY, LOCAL_PREFERRED, DISTRIBUTED_PREFERRED, DISTRIBUTED_ONLY).
         * Empty = keep [FederationCacheRequirements] defaults.
         */
        const val HTTP_RESOLVER_LOCALITY = "$PREFIX.http.resolver.locality"
        const val ENTITY_CONFIG_LOCALITY = "$PREFIX.entity.config.locality"
        const val TRUST_CHAIN_LOCALITY = "$PREFIX.trust.chain.locality"
        const val TRUST_MARK_LOCALITY = "$PREFIX.trust.mark.locality"
    }

    // ========================================================================
    // JWE (optional encrypted payloads via IDK JweService)
    // ========================================================================

    object Jwe {
        const val PREFIX = "$OIDF_PREFIX.jwe"

        /**
         * Enable optional JWE helpers for hosts that inject IDK JweService:
         * `true` | `false` (default false — federation statements stay JWS-only).
         */
        const val ENABLED = "$PREFIX.enabled"
    }

    // ========================================================================
    // KMS Configuration (APP scope)
    // ========================================================================

    object Kms {
        const val PREFIX = "$OIDF_PREFIX.kms"

        /** Default KMS provider ID */
        const val DEFAULT_PROVIDER = "$PREFIX.default.provider"

        /**
         * KMS Provider configuration follows IDK pattern:
         * kms.providers.<id>.type
         * kms.providers.<id>.enabled
         * etc.
         */
        object Providers {
            const val PREFIX = "kms.providers"

            fun type(providerId: String) = "$PREFIX.$providerId.type"
            fun enabled(providerId: String) = "$PREFIX.$providerId.enabled"
            fun order(providerId: String) = "$PREFIX.$providerId.order"
            fun id(providerId: String) = "$PREFIX.$providerId.id"
        }
    }

    // ========================================================================
    // Identity / multi-tenancy mode (APP scope)
    // ========================================================================

    object Identity {
        const val PREFIX = "$OIDF_PREFIX.identity"

        /**
         * Identity mode: `account` (default) or `external`.
         * Aliases: `legacy`→account, `platform`→external.
         * @see com.sphereon.openid.fed.core.tenant.IdentityMode
         */
        const val MODE = "$PREFIX.mode"

        /**
         * Optional token tenant id that owns the federation root **entity URL**
         * (EXTERNAL mode identifier mapping only — not login, not session fallback).
         */
        const val EXTERNAL_ROOT_TENANT_ID = "$PREFIX.external.root.tenant.id"

        /** Compat alias for [EXTERNAL_ROOT_TENANT_ID]. */
        const val PLATFORM_ROOT_TENANT_ID = "$PREFIX.platform.root.tenant.id"

        /**
         * IDK session tenant alignment strategy (L1 vs L2).
         *
         * - `account` (default): ACCOUNT mode binds session tenant to Account.id (L2)
         * - `fixed`: always use a fixed session tenant id (L1 compat)
         */
        const val SESSION_ALIGNMENT = "$PREFIX.session.alignment"

        /**
         * Fixed session tenant id when [SESSION_ALIGNMENT] is `fixed`, or lookup fallback (default `default`).
         */
        const val SESSION_FIXED_TENANT_ID = "$PREFIX.session.fixed.tenant.id"

        /**
         * ACCOUNT mode: JWT claim name matched against [ACCOUNT_HEADER_ALLOWED_PRINCIPALS]
         * when authorizing `X-Account-Username` (default `sub`).
         */
        const val ACCOUNT_HEADER_PRINCIPAL_CLAIM = "$PREFIX.account.header.principal.claim"

        /**
         * ACCOUNT mode: comma-separated principal values allowed to use `X-Account-Username`.
         * Default blank = **nobody** (no header rebind). Explicit `*` = any authenticated
         * (test/dev only). Example production: `ops-admin,service-bot`.
         */
        const val ACCOUNT_HEADER_ALLOWED_PRINCIPALS = "$PREFIX.account.header.allowed.principals"
    }

    // ========================================================================
    // Tenant Configuration (TENANT scope)
    // ========================================================================

    /**
     * Per-tenant overrides (APP catalog keys `oidf.tenant.<id>.*`).
     *
     * These map to settings that may also be set as bare keys on IDK tenant-scoped
     * property sources (e.g. `oidf.kms.default.provider` on [TenantConfigService]).
     * Process-fixed keys (server ports, datasource, identity.mode, oauth2 issuer) are not here.
     */
    object Tenant {
        const val PREFIX = "$OIDF_PREFIX.tenant"

        /** Get tenant-specific property key */
        fun key(tenantId: String, property: String) = "$PREFIX.$tenantId.$property"

        /** Tenant-specific root identifier → [Federation.ROOT_IDENTIFIER] */
        fun rootIdentifier(tenantId: String) = key(tenantId, "federation.root.identifier")

        /** Tenant-specific KMS provider → [Kms.DEFAULT_PROVIDER] */
        fun kmsProvider(tenantId: String) = key(tenantId, "kms.provider")

        fun cacheHttpResolverLocality(tenantId: String) = key(tenantId, "cache.http.resolver.locality")
        fun cacheTrustChainLocality(tenantId: String) = key(tenantId, "cache.trust.chain.locality")
        fun cacheEntityConfigLocality(tenantId: String) = key(tenantId, "cache.entity.config.locality")
        fun cacheTrustMarkLocality(tenantId: String) = key(tenantId, "cache.trust.mark.locality")

        fun accountHeaderPrincipalClaim(tenantId: String) =
            key(tenantId, "identity.account.header.principal.claim")

        fun accountHeaderAllowedPrincipals(tenantId: String) =
            key(tenantId, "identity.account.header.allowed.principals")
    }

    // ========================================================================
    // Principal Configuration (PRINCIPAL scope - future use)
    // ========================================================================

    object Principal {
        const val PREFIX = "$OIDF_PREFIX.principal"

        /** Get principal-specific property key */
        fun key(principalId: String, property: String) = "$PREFIX.$principalId.$property"
    }
}
