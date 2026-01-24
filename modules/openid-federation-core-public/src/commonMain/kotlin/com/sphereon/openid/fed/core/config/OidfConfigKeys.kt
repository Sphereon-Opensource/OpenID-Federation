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
    // Tenant Configuration (TENANT scope)
    // ========================================================================

    object Tenant {
        const val PREFIX = "$OIDF_PREFIX.tenant"

        /** Get tenant-specific property key */
        fun key(tenantId: String, property: String) = "$PREFIX.$tenantId.$property"

        /** Tenant-specific root identifier */
        fun rootIdentifier(tenantId: String) = key(tenantId, "federation.root.identifier")

        /** Tenant-specific KMS provider */
        fun kmsProvider(tenantId: String) = key(tenantId, "kms.provider")
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
