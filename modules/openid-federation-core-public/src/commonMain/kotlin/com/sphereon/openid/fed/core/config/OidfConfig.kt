package com.sphereon.openid.fed.core.config

import com.sphereon.openid.fed.core.tenant.IdentityConfig
import com.sphereon.openid.fed.core.tenant.IdentityMode

/**
 * Federation core configuration settings (APP scope).
 */
data class FederationConfig(
    /** Base URL identifier for the federation root entity */
    val rootIdentifier: String = "http://localhost:8080",
    /** Enable development mode with verbose logging */
    val devMode: Boolean = false,
    /**
     * Client authentication methods accepted at federation protocol endpoints (OIDFed §8.8).
     * Default per-endpoint is `["none"]` when omitted from config.
     * Values: `none`, `private_key_jwt` (comma-separated when multiple).
     */
    val endpointAuthMethods: FederationEndpointAuthMethods = FederationEndpointAuthMethods(),
    /**
     * JWS algs advertised/accepted for `private_key_jwt` endpoint authentication
     * (`endpoint_auth_signing_alg_values_supported`).
     */
    val endpointAuthSigningAlgs: List<String> = listOf("RS256", "ES256", "PS256"),
    /**
     * How a `private_key_jwt` client is accepted as a federation participant
     * (OIDFed §8.8 — ClientRegistry analogue for Entity Identifiers).
     */
    val endpointAuthMembershipPolicy: FederationClientAuthMembershipPolicy =
        FederationClientAuthMembershipPolicy.HYBRID,
    /**
     * Trust Anchors used when membership policy requires a trust chain.
     * Empty = use the host Entity Identifier as the sole Trust Anchor.
     */
    val endpointAuthTrustAnchors: List<String> = emptyList(),
)

/**
 * Membership check for entities authenticating to federation endpoints (§8.8).
 *
 * Analogous to OAuth AS [ClientRegistry] membership, but the authority is the
 * federation graph rather than OAuth client registration.
 */
enum class FederationClientAuthMembershipPolicy {
    /** Any entity whose Entity Configuration can be fetched (keys only). */
    ANY_FETCHABLE,

    /** Client Entity Identifier must be an Immediate Subordinate of the host tenant. */
    SUBORDINATE_OF_SELF,

    /** Client must have a valid Trust Chain to a configured Trust Anchor. */
    TRUST_CHAIN_TO_TA,

    /**
     * Prefer Immediate Subordinate of the host; otherwise require Trust Chain to TA.
     * Recommended default for TA/intermediate endpoints.
     */
    HYBRID,
    ;

    companion object {
        fun fromConfig(raw: String): FederationClientAuthMembershipPolicy {
            val n = raw.trim().lowercase().replace('-', '_')
            return when (n) {
                "any_fetchable", "any", "open" -> ANY_FETCHABLE
                "subordinate_of_self", "subordinate", "local" -> SUBORDINATE_OF_SELF
                "trust_chain_to_ta", "trust_chain", "chain" -> TRUST_CHAIN_TO_TA
                "hybrid", "subordinate_or_chain" -> HYBRID
                else -> HYBRID
            }
        }
    }
}

/**
 * Per-endpoint client authentication methods (OIDFed §8.8 `*_auth_methods`).
 * Spec default when omitted from metadata is `["none"]`.
 */
data class FederationEndpointAuthMethods(
    val fetch: List<String> = listOf("none"),
    val list: List<String> = listOf("none"),
    val resolve: List<String> = listOf("none"),
    val trustMarkStatus: List<String> = listOf("none"),
    val trustMarkList: List<String> = listOf("none"),
    val trustMark: List<String> = listOf("none"),
    val historicalKeys: List<String> = listOf("none"),
) {
    fun forEndpoint(endpoint: FederationEndpointKind): List<String> = when (endpoint) {
        FederationEndpointKind.FETCH -> fetch
        FederationEndpointKind.LIST -> list
        FederationEndpointKind.RESOLVE -> resolve
        FederationEndpointKind.TRUST_MARK_STATUS -> trustMarkStatus
        FederationEndpointKind.TRUST_MARK_LIST -> trustMarkList
        FederationEndpointKind.TRUST_MARK -> trustMark
        FederationEndpointKind.HISTORICAL_KEYS -> historicalKeys
    }

    /** True if any endpoint accepts `private_key_jwt`. */
    fun anyPrivateKeyJwt(): Boolean =
        listOf(fetch, list, resolve, trustMarkStatus, trustMarkList, trustMark, historicalKeys)
            .any { methods -> methods.any { it.equals("private_key_jwt", ignoreCase = true) } }
}

/** Federation protocol endpoints that may declare `*_auth_methods` (OIDFed §8.8.1). */
enum class FederationEndpointKind {
    FETCH,
    LIST,
    RESOLVE,
    TRUST_MARK_STATUS,
    TRUST_MARK_LIST,
    TRUST_MARK,
    HISTORICAL_KEYS,
    ;

    /** Metadata parameter name, e.g. `federation_fetch_endpoint_auth_methods`. */
    val authMethodsMetadataName: String
        get() = when (this) {
            FETCH -> "federation_fetch_endpoint_auth_methods"
            LIST -> "federation_list_endpoint_auth_methods"
            RESOLVE -> "federation_resolve_endpoint_auth_methods"
            TRUST_MARK_STATUS -> "federation_trust_mark_status_endpoint_auth_methods"
            TRUST_MARK_LIST -> "federation_trust_mark_list_endpoint_auth_methods"
            TRUST_MARK -> "federation_trust_mark_endpoint_auth_methods"
            HISTORICAL_KEYS -> "federation_historical_keys_endpoint_auth_methods"
        }
}

/**
 * Server configuration for admin or federation server.
 */
data class ServerConfig(
    /** Port number to bind the server */
    val port: Int,
    /** Host address to bind the server */
    val host: String = "0.0.0.0"
)

/**
 * CORS (Cross-Origin Resource Sharing) configuration.
 */
data class CorsConfig(
    /** List of allowed origins (use "*" for any) */
    val allowedOrigins: List<String> = listOf("*"),
    /** List of allowed HTTP methods */
    val allowedMethods: List<String> = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS"),
    /** List of allowed headers (use "*" for any) */
    val allowedHeaders: List<String> = listOf("*"),
    /** Max age in seconds for preflight cache */
    val maxAge: Long = 3600
)

/**
 * Logger configuration settings.
 */
data class LoggerConfig(
    /** Log severity level: VERBOSE, DEBUG, INFO, WARN, ERROR */
    val severity: String = "INFO",
    /** Output format: TEXT or JSON */
    val output: String = "TEXT",
    /** Include timestamps in log output */
    val includeTimestamp: Boolean = false
)

/**
 * Database/datasource configuration.
 */
data class DatasourceConfig(
    /** JDBC connection URL */
    val url: String = "",
    /** Database username */
    val user: String = "",
    /** Database password */
    val password: String = "",
    /** Optional database name (for URL construction) */
    val db: String = ""
)

/**
 * OAuth2 / JWT configuration for PLATFORM authentication.
 *
 * @property issuerUri OIDC issuer URI for JWT validation (empty = JWT auth not auto-installed)
 * @property audience Optional expected `aud` claim override
 * @property jwtAuthEnabled `auto` | `true` | `false` — auto enables when PLATFORM + issuer set
 */
data class OAuth2Config(
    /** OIDC issuer URI for JWT validation */
    val issuerUri: String = "",
    /** Optional expected audience for access tokens */
    val audience: String = "",
    /**
     * Whether to install IDK [JwtAuthentication]:
     * - `auto` (default): enable when identity.mode=platform and issuerUri is non-blank
     * - `true` / `false`: force on/off
     */
    val jwtAuthEnabled: String = "auto",
)

/**
 * KMS (Key Management Service) provider configuration.
 */
data class KmsConfig(
    /** Default KMS provider ID: memory, azure, aws */
    val defaultProvider: String = "memory"
)

/**
 * Tenant-level overrides for settings that may differ per federation tenant / IDK tenant.
 *
 * Loaded from `oidf.tenant.<tenantId>.*` (APP catalog) and/or session [com.sphereon.core.api.conf.TenantConfigService]
 * bare keys via [OidfConfigBinder] effective helpers. Process-fixed settings (ports, datasource,
 * identity **mode**, OAuth2 issuer) are not included.
 */
data class TenantConfig(
    /** Tenant identifier */
    val tenantId: String,
    /** Override federation root entity identifier URL */
    val rootIdentifier: String? = null,
    /** Override default KMS provider id */
    val kmsProvider: String? = null,
    /** Override cache locality for HTTP resolver (enum name) */
    val cacheHttpResolverLocality: String? = null,
    /** Override cache locality for trust chain */
    val cacheTrustChainLocality: String? = null,
    /** Override cache locality for entity config */
    val cacheEntityConfigLocality: String? = null,
    /** Override cache locality for trust marks */
    val cacheTrustMarkLocality: String? = null,
    /**
     * ACCOUNT mode: JWT claim for header allow-list (override APP default `sub`).
     * Does **not** change identity.mode.
     */
    val accountHeaderPrincipalClaim: String? = null,
    /**
     * ACCOUNT mode: principals allowed to use X-Account-Username (comma-separated when from env/file).
     */
    val accountHeaderAllowedPrincipals: List<String>? = null,
) {
    fun hasAnyOverride(): Boolean =
        rootIdentifier != null ||
            kmsProvider != null ||
            cacheHttpResolverLocality != null ||
            cacheTrustChainLocality != null ||
            cacheEntityConfigLocality != null ||
            cacheTrustMarkLocality != null ||
            accountHeaderPrincipalClaim != null ||
            accountHeaderAllowedPrincipals != null
}

/**
 * Complete application configuration aggregate.
 * Combines all configuration sections for convenient access.
 */
data class OidfAppConfig(
    val federation: FederationConfig = FederationConfig(),
    val adminServer: ServerConfig = ServerConfig(port = 8081),
    val federationServer: ServerConfig = ServerConfig(port = 8080),
    val cors: CorsConfig = CorsConfig(),
    val logger: LoggerConfig = LoggerConfig(),
    val datasource: DatasourceConfig = DatasourceConfig(),
    val oauth2: OAuth2Config = OAuth2Config(),
    val kms: KmsConfig = KmsConfig(),
    val identity: IdentityConfig = IdentityConfig()
)

// Re-export identity types for config consumers
typealias OidfIdentityMode = IdentityMode
typealias OidfIdentityConfig = IdentityConfig
