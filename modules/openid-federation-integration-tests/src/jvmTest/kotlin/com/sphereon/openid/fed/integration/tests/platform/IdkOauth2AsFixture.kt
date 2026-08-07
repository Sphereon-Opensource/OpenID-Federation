package com.sphereon.openid.fed.integration.tests.platform

import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.core.api.conf.DefaultPrincipalMapPropertySource
import com.sphereon.core.defaults.context.DefaultPrincipalInputString
import com.sphereon.core.defaults.context.DefaultTenantInputString
import com.sphereon.oauth2.server.authorization.command.CreateAccessTokenArgs
import com.sphereon.oauth2.server.authorization.command.CreateAccessTokenCommand
import com.sphereon.oauth2.server.authorization.impl.bootstrap.ensureActiveSigningKeyBlocking
import com.sphereon.oauth2.server.authorization.ktor.OAuth2AsAppGraph
import com.sphereon.oauth2.server.authorization.ktor.configureOAuth2As
import com.sphereon.oauth2.server.authorization.ktor.createOAuth2AsAppGraph
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking

/**
 * In-process **IDK OAuth2 Authorization Server** (`services-oauth2-as-rest`).
 *
 * Mirrors [com.sphereon.oauth2.oidf.op.OidfOpServerFixture] / `OAuth2AsKtorServer.main`:
 * 1. Seed software KMS on IDK property maps (same `kms.providers.software.*` as OIDF OP harness)
 * 2. [createOAuth2AsAppGraph]
 * 3. [ensureActiveSigningKeyBlocking] so mints are real JWTs (not opaque fallbacks)
 * 4. Ktor [configureOAuth2As] on an ephemeral port (discovery, JWKS, /token, …)
 *
 * Tokens are minted with the real [CreateAccessTokenCommand] (same path as the token
 * endpoint). Optional [tenantId] is passed as `additionalClaims["tenant_id"]` for PLATFORM
 * isolation tests (OIDFed [com.sphereon.openid.fed.core.tenant.PlatformJwtTenantClaims]).
 *
 * ## Why not hand-rolled ES256
 * PLATFORM e2e must exercise the IDK OP stack end-to-end (signing key store, JwtService,
 * discovery/JWKS). See `D:/git/VDX-infra/vdx/edk/idk/services/oauth2-as`.
 */
class IdkOauth2AsFixture(
    private val audience: String = DEFAULT_AUDIENCE,
    appId: String = "oidf-platform-e2e-as",
    profile: String = "test",
) {
    val graph: OAuth2AsAppGraph
    private val server: io.ktor.server.engine.EmbeddedServer<*, *>

    init {
        // Must run BEFORE AppGraph: KeyManagerService resolves providers from IDK property maps.
        seedSoftwareKms(appId = appId, profile = profile)

        graph =
            createOAuth2AsAppGraph(
                application = this,
                appId = appId,
                profile = profile,
                version = "test",
            )

        val keyResult =
            ensureActiveSigningKeyBlocking(
                appCommandInvoker = graph.appCommandInvoker,
                signingKeyStore = graph.signingKeyStore,
                tenantInput = DefaultTenantInputString(DEFAULT_TENANT_ID),
                principalInput = DefaultPrincipalInputString(DEFAULT_PRINCIPAL_ID),
                tenantId = DEFAULT_TENANT_ID,
            )
        check(!keyResult.isErr) {
            "AS signing-key bootstrap failed: ${keyResult.error}"
        }

        server =
            embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                configureOAuth2As(graph)
            }
        server.start(wait = false)
    }

    val port: Int by lazy {
        runBlocking {
            server.engine.resolvedConnectors().first().port
        }
    }

    /** Base URL of the live in-process OP (loopback HTTP is IDK-allowed for discovery). */
    val baseUrl: String get() = "http://127.0.0.1:$port"

    /**
     * Issuer string for resource-server config (`OIDF_OAUTH2_ISSUER_URI`).
     * Matches `iss` on minted access tokens (`baseUrlOverride` = [baseUrl]).
     */
    val issuer: String get() = baseUrl

    /**
     * Mint a real AS access token via [CreateAccessTokenCommand].
     *
     * @param tenantId PLATFORM isolation claim (`tenant_id`); omit for LEGACY (entity via header)
     * @param subject token `sub`
     * @param clientId OAuth client id claim
     * @param expiresInSeconds lifetime
     * @param extraClaims merged into token payload (does not override [tenantId] unless also set here)
     */
    fun mintAccessToken(
        tenantId: String? = null,
        subject: String = "platform-e2e-user",
        clientId: String = DEFAULT_CLIENT_ID,
        expiresInSeconds: Int = 3600,
        scope: String? = "openid federation.admin",
        extraClaims: Map<String, Any> = emptyMap(),
    ): String =
        runBlocking {
            // Same dispatch path as ensureActiveSigningKey / token endpoint internals:
            // AppCommandInvoker opens a session and runs CreateAccessTokenCommand.
            val createAccessToken =
                graph.appCommandInvoker.resolve(CreateAccessTokenCommand.COMMAND_ID)
                    as? CreateAccessTokenCommand
                    ?: error(
                        "CreateAccessTokenCommand not registered on AS graph " +
                            "(need com.sphereon.idk:services-oauth2-as-rest + authorization-impl)",
                    )

            val claims = buildMap {
                putAll(extraClaims)
                if (tenantId != null) {
                    put("tenant_id", tenantId)
                }
            }
            val result =
                graph.appCommandInvoker.execute(
                    tenantInput = DefaultTenantInputString(DEFAULT_TENANT_ID),
                    principalInput = DefaultPrincipalInputString(DEFAULT_PRINCIPAL_ID),
                    command = createAccessToken,
                    input =
                        CreateAccessTokenArgs(
                            subject = subject,
                            clientId = clientId,
                            scope = scope,
                            audience = listOf(audience),
                            expiresInSeconds = expiresInSeconds,
                            additionalClaims = claims,
                            baseUrlOverride = baseUrl,
                        ),
                )
            check(result.isOk) {
                "CreateAccessTokenCommand failed: ${if (result.isErr) result.error.message.defaultMessage else "<unknown>"}"
            }
            result.value.value
        }

    fun stop() {
        server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
    }

    companion object {
        const val DEFAULT_AUDIENCE: String = "openid-federation-admin"
        const val DEFAULT_CLIENT_ID: String = "oidf-platform-e2e"
        const val DEFAULT_TENANT_ID: String = "default"
        const val DEFAULT_PRINCIPAL_ID: String = "oauth2-as"

        /**
         * IDK OIDF OP harness pattern (`application.properties` kms.providers.software.*):
         * APP-scoped memory keystore so bootstrap keys remain visible on per-request sessions.
         */
        fun seedSoftwareKms(
            appId: String,
            profile: String,
        ) {
            val props =
                mapOf(
                    "kms.providers.software.type" to "software",
                    "kms.providers.software.id" to "software",
                    "kms.providers.software.enabled" to "true",
                    "kms.providers.software.keystore.type" to "memory",
                    "kms.providers.software.keystore.scope-binding" to "APP",
                    "kms.providers.software.keystore.key-visibility" to "private",
                    "kms.providers.software.keystore.id" to "oidf-platform-e2e-as-keystore",
                    // Namespaced keys for binders expecting appId.profile
                    "$appId.$profile.kms.providers.software.type" to "software",
                    "$appId.$profile.kms.providers.software.id" to "software",
                    "$appId.$profile.kms.providers.software.enabled" to "true",
                    "$appId.$profile.kms.providers.software.keystore.type" to "memory",
                    "$appId.$profile.kms.providers.software.keystore.scope-binding" to "APP",
                    "$appId.$profile.kms.providers.software.keystore.key-visibility" to "private",
                    "$appId.$profile.kms.providers.software.keystore.id" to "oidf-platform-e2e-as-keystore",
                    // JWT access tokens from CreateAccessTokenCommand
                    "oauth2.servers.default-server" to "default",
                    "oauth2.servers.default.mode" to "HOSTED",
                    "oauth2.servers.default.oidc" to "SUPPORTED",
                    "oauth2.servers.default.token-format" to "JWT",
                    "oauth2.servers.default.signing-key-alias" to "oauth2-server-signing",
                )
            val missing =
                props.filterKeys { key ->
                    DefaultAppMapPropertySource.getPropertyAsString(key).isNullOrEmpty()
                }
            if (missing.isNotEmpty()) {
                DefaultAppMapPropertySource.addProperties(missing)
                DefaultPrincipalMapPropertySource.addProperties(
                    missing.filterKeys { !it.startsWith("$appId.") },
                )
            }
        }
    }
}
