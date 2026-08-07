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
    val devMode: Boolean = false
)

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
 * Tenant-specific configuration overrides (TENANT scope).
 */
data class TenantConfig(
    /** Tenant identifier */
    val tenantId: String,
    /** Optional tenant-specific root identifier override */
    val rootIdentifier: String? = null,
    /** Optional tenant-specific KMS provider override */
    val kmsProvider: String? = null
)

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
