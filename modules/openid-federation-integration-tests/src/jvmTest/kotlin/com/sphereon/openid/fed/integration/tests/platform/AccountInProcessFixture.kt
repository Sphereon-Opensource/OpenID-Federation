package com.sphereon.openid.fed.integration.tests.platform

import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.openid.fed.core.config.OidfConfigBootstrap
import com.sphereon.openid.fed.core.config.OidfConfigKeys
import com.sphereon.openid.fed.server.admin.ktor.configureAdmin
import com.sphereon.openid.fed.server.admin.ktor.di.AdminServerAppGraph
import com.sphereon.openid.fed.server.admin.ktor.di.createAdminServerAppGraph
import com.sphereon.openid.fed.server.federation.ktor.configureFederation
import com.sphereon.openid.fed.server.federation.ktor.di.FederationServerAppGraph
import com.sphereon.openid.fed.server.federation.ktor.di.createFederationServerAppGraph
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking

/**
 * In-process **ACCOUNT** stack for authenticated integration tests:
 *
 * 1. [IdkOauth2AsFixture] — JWT issuer for admin
 * 2. Admin server — always requires Bearer; entity via JWT-first open + optional X-Account-Username rebind
 * 3. Federation public server — protocol endpoints (no Bearer required)
 *
 * Shared DB with Postgres (same as external docker). Used when
 * `ADMIN_SERVER_BASE_URL` / `FEDERATION_SERVER_BASE_URL` are unset.
 */
class AccountInProcessFixture(
    audience: String = IdkOauth2AsFixture.DEFAULT_AUDIENCE,
) {
    val oauthAs: IdkOauth2AsFixture = IdkOauth2AsFixture(audience = audience)

    val adminGraph: AdminServerAppGraph
    val federationGraph: FederationServerAppGraph
    private val adminServer: EmbeddedServer<*, *>
    private val federationServer: EmbeddedServer<*, *>

    val adminBaseUrl: String get() = "http://127.0.0.1:$adminPort"
    val federationBaseUrl: String get() = "http://127.0.0.1:$federationPort"

    private val adminPort: Int by lazy {
        runBlocking {
            adminServer.engine.resolvedConnectors().first().port
        }
    }

    private val federationPort: Int by lazy {
        runBlocking {
            federationServer.engine.resolvedConnectors().first().port
        }
    }

    @Volatile
    private var cachedToken: String? = null

    init {
        seedAccountConfig(
            issuerUri = oauthAs.issuer,
            audience = audience,
        )

        OidfConfigBootstrap.seed(
            appId = ADMIN_APP_ID,
            profile = PROFILE,
            loadFileDefaults = true,
            seedSoftwareKms = true,
        )

        adminGraph =
            createAdminServerAppGraph(
                application = this,
                appId = ADMIN_APP_ID,
                profile = PROFILE,
            )

        // Federation graph shares AppMap / DB; distinct appId namespace for server config.
        OidfConfigBootstrap.seed(
            appId = FED_APP_ID,
            profile = PROFILE,
            loadFileDefaults = true,
            seedSoftwareKms = true,
        )
        federationGraph =
            createFederationServerAppGraph(
                application = this,
                appId = FED_APP_ID,
                profile = PROFILE,
            )

        val adminConfig = adminGraph.serverConfig
        adminServer =
            embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                configureAdmin(adminGraph, adminConfig)
            }
        adminServer.start(wait = false)
        check(adminPort in 1..65535)

        val fedConfig = federationGraph.serverConfig
        federationServer =
            embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                configureFederation(federationGraph, fedConfig)
            }
        federationServer.start(wait = false)
        check(federationPort in 1..65535)
    }

    /**
     * Cached Bearer for ACCOUNT-mode suite.
     * Subject matches [ACCOUNT_E2E_SUBJECT] so it is on the fixture header allow-list
     * (production default allow-list is empty — no header rebind).
     */
    fun defaultAccessToken(): String {
        cachedToken?.let { return it }
        val minted =
            oauthAs.mintAccessToken(
                tenantId = null,
                subject = ACCOUNT_E2E_SUBJECT,
            )
        cachedToken = minted
        return minted
    }

    fun mintAccessToken(): String =
        oauthAs.mintAccessToken(tenantId = null, subject = ACCOUNT_E2E_SUBJECT)

    fun stop() {
        try {
            adminServer.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        } finally {
            try {
                federationServer.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
            } finally {
                oauthAs.stop()
            }
        }
    }

    companion object {
        const val ADMIN_APP_ID = "openid-federation-admin-server"
        const val FED_APP_ID = "openid-federation-server"
        const val PROFILE = "default"
        /** JWT `sub` allow-listed for X-Account-Username rebind in this fixture. */
        const val ACCOUNT_E2E_SUBJECT = "account-e2e-user"

        /**
         * Process-wide fixture so all commonTest classes share one AS + admin + federation.
         */
        val shared: AccountInProcessFixture by lazy { AccountInProcessFixture() }

        fun seedAccountConfig(
            issuerUri: String,
            audience: String,
        ) {
            val dsUrl =
                System.getenv("OIDF_DATASOURCE_URL")
                    ?: System.getenv("DATASOURCE_URL")
                    ?: "jdbc:postgresql://localhost:5432/openid-federation-db"
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
                    OidfConfigKeys.Identity.MODE to "account",
                    // Header rebind only for the fixture operator principal (not *).
                    OidfConfigKeys.Identity.ACCOUNT_HEADER_PRINCIPAL_CLAIM to "sub",
                    OidfConfigKeys.Identity.ACCOUNT_HEADER_ALLOWED_PRINCIPALS to ACCOUNT_E2E_SUBJECT,
                    OidfConfigKeys.OAuth2.ISSUER_URI to issuerUri,
                    OidfConfigKeys.OAuth2.AUDIENCE to audience,
                    OidfConfigKeys.OAuth2.JWT_AUTH_ENABLED to "true",
                    OidfConfigKeys.Datasource.URL to hostLocalDs,
                    OidfConfigKeys.Datasource.USER to dsUser,
                    OidfConfigKeys.Datasource.PASSWORD to dsPass,
                    OidfConfigKeys.Federation.ROOT_IDENTIFIER to "http://localhost:8080",
                )
            props.forEach { (k, v) ->
                DefaultAppMapPropertySource.addProperty(k, v)
            }
        }
    }
}
