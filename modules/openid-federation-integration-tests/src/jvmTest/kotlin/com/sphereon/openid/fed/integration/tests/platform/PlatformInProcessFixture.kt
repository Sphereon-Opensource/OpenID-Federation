package com.sphereon.openid.fed.integration.tests.platform

import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.openid.fed.core.config.OidfConfigBootstrap
import com.sphereon.openid.fed.core.config.OidfConfigKeys
import com.sphereon.openid.fed.server.admin.ktor.configureAdmin
import com.sphereon.openid.fed.server.admin.ktor.di.AdminServerAppGraph
import com.sphereon.openid.fed.server.admin.ktor.di.createAdminServerAppGraph
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking

/**
 * Fully **in-process** PLATFORM stack for e2e:
 *
 * 1. [IdkOauth2AsFixture] — real IDK OAuth2 AS (mint + discovery + JWKS)
 * 2. OIDFed admin — [createAdminServerAppGraph] + [configureAdmin] on an ephemeral port,
 *    configured for PLATFORM + JWT issuer = the live AS base URL
 *
 * No standalone admin process, no Keycloak, no fixed-port orchestration.
 * Requires PostgreSQL (same as LEGACY integration tests).
 *
 * @see docs/PLATFORM_E2E.md
 */
class PlatformInProcessFixture(
    audience: String = IdkOauth2AsFixture.DEFAULT_AUDIENCE,
) {
    val oauthAs: IdkOauth2AsFixture = IdkOauth2AsFixture(audience = audience)

    val adminGraph: AdminServerAppGraph
    private val adminServer: EmbeddedServer<*, *>

    val adminBaseUrl: String get() = "http://127.0.0.1:$adminPort"
    val asBaseUrl: String get() = oauthAs.baseUrl

    private val adminPort: Int by lazy {
        runBlocking {
            adminServer.engine.resolvedConnectors().first().port
        }
    }

    init {
        // Point admin JWT validation at the already-running in-process AS.
        seedPlatformAdminConfig(
            issuerUri = oauthAs.issuer,
            audience = audience,
        )

        OidfConfigBootstrap.seed(
            appId = ADMIN_APP_ID,
            profile = ADMIN_PROFILE,
            loadFileDefaults = true,
            seedSoftwareKms = true,
        )

        adminGraph =
            createAdminServerAppGraph(
                application = this,
                appId = ADMIN_APP_ID,
                profile = ADMIN_PROFILE,
            )

        val config = adminGraph.serverConfig
        adminServer =
            embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                configureAdmin(adminGraph, config)
            }
        adminServer.start(wait = false)
        // Force port resolution early so tests can print baseUrl immediately
        check(adminPort in 1..65535)
    }

    fun mintAccessToken(
        tenantId: String,
        subject: String = "platform-e2e-user",
    ): String = oauthAs.mintAccessToken(tenantId = tenantId, subject = subject)

    fun stop() {
        try {
            adminServer.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        } finally {
            oauthAs.stop()
        }
    }

    companion object {
        const val ADMIN_APP_ID = "openid-federation-admin-server"
        const val ADMIN_PROFILE = "default"
        /** UUID-shaped root tenant (account_id columns use UUID adapters). */
        const val PLATFORM_ROOT_TENANT = "00000000-0000-4000-8000-000000000001"

        /**
         * Explicit AppMap overrides (highest binder tier after AppConfigService).
         * Datasource falls through to env / file when not set here.
         */
        fun seedPlatformAdminConfig(
            issuerUri: String,
            audience: String,
        ) {
            val dsUrl =
                System.getenv("OIDF_DATASOURCE_URL")
                    ?: System.getenv("DATASOURCE_URL")
                    ?: "jdbc:postgresql://localhost:5432/openid-federation-db"
            // Host-side tests must not use docker-compose hostname "db"
            val hostLocalDs =
                if (dsUrl.contains("://db:") || dsUrl.contains("@db:")) {
                    dsUrl.replace("://db:", "://localhost:").replace("@db:", "@localhost:")
                } else {
                    dsUrl
                }
            val dsUser =
                System.getenv("OIDF_DATASOURCE_USER")
                    ?: System.getenv("DATASOURCE_USER")
                    ?: "openid-federation-db-user"
            val dsPass =
                System.getenv("OIDF_DATASOURCE_PASSWORD")
                    ?: System.getenv("DATASOURCE_PASSWORD")
                    ?: "openid-federation-db-password"

            val props =
                mapOf(
                    OidfConfigKeys.Identity.MODE to "platform",
                    OidfConfigKeys.Identity.PLATFORM_ROOT_TENANT_ID to PLATFORM_ROOT_TENANT,
                    OidfConfigKeys.Identity.ALLOW_ANONYMOUS_ADMIN to "false",
                    OidfConfigKeys.OAuth2.ISSUER_URI to issuerUri,
                    OidfConfigKeys.OAuth2.AUDIENCE to audience,
                    OidfConfigKeys.OAuth2.JWT_AUTH_ENABLED to "true",
                    OidfConfigKeys.Datasource.URL to hostLocalDs,
                    OidfConfigKeys.Datasource.USER to dsUser,
                    OidfConfigKeys.Datasource.PASSWORD to dsPass,
                    OidfConfigKeys.Federation.ROOT_IDENTIFIER to "http://localhost:8080",
                )
            // Force overwrite so a prior LEGACY process env/map cannot win
            props.forEach { (k, v) ->
                DefaultAppMapPropertySource.addProperty(k, v)
            }
        }
    }
}
