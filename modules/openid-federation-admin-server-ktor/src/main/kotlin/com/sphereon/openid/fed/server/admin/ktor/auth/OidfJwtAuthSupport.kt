package com.sphereon.openid.fed.server.admin.ktor.auth

import com.sphereon.core.api.log.Log
import com.sphereon.core.defaults.context.JwtClaimsParser
import com.sphereon.core.defaults.context.markValidated
import com.sphereon.di.context.IdentityConstants
import com.sphereon.di.session.SessionInstance
import com.sphereon.ktor.server.inject.BaseTenantIdAttribute
import com.sphereon.ktor.server.inject.ValidatedJwtClaimsAttribute
import com.sphereon.ktor.server.inject.interceptor.UserContextInterceptor
import com.sphereon.ktor.server.inject.kotlinInject
import com.sphereon.ktor.server.jwt.JwtAuthentication
import com.sphereon.ktor.server.jwt.SessionContextAttributeKey
import com.sphereon.openid.fed.core.config.OAuth2Config
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.tenant.BearerTokenSupport
import com.sphereon.openid.fed.core.tenant.IdentityMode
import com.sphereon.openid.fed.core.tenant.PlatformJwtTenantClaims
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.util.AttributeKey

private val logger = Log.app().withTag("OidfJwtAuthSupport")

/**
 * Install IDK [JwtAuthentication] for PLATFORM (or forced) deployments.
 *
 * ## Plugin order (required)
 * 1. [com.sphereon.ktor.server.inject.KotlinInjectPlugin] — bootstrap session graph
 *    (tenant may be platform root / fixed — JWT claims not stamped yet)
 * 2. [JwtAuthentication] — validates bearer, builds [SessionContextAttributeKey]
 * 3. [OidfStampValidatedJwtClaimsPlugin] — stamps [ValidatedJwtClaimsAttribute]
 * 4. [OidfJwtSessionRebindPlugin] — if JWT tenant ≠ bootstrap DI tenant, recreate
 *    the kotlin-inject user/session graph so [com.sphereon.core.api.context.SessionExecution.tenantId]
 *    matches the validated JWT tenant (KMS/config/cache scopes follow JWT)
 * 5. Fallback cleanup destroys the rebound session (bootstrap session is still
 *    destroyed by KotlinInjectPlugin's finally block)
 *
 * ## When enabled
 * - `oidf.oauth2.jwt.auth.enabled=true`, or
 * - `auto` (default) and identity.mode=platform and issuer URI is non-blank
 *
 * LEGACY open-source defaults leave JWT auth **off** so existing integration tests
 * keep working without an IdP.
 */
object OidfJwtAuthSupport {

    fun shouldInstall(configBinder: OidfConfigBinder): Boolean {
        val oauth = configBinder.getOAuth2Config()
        val identity = configBinder.getIdentityConfig()
        return when (oauth.jwtAuthEnabled.trim().lowercase()) {
            "true", "1", "yes", "on" -> oauth.issuerUri.isNotBlank()
            "false", "0", "no", "off" -> false
            else -> // auto
                identity.mode == IdentityMode.PLATFORM && oauth.issuerUri.isNotBlank()
        }
    }

    /**
     * @param requireAuth When true, missing/invalid tokens yield 401 (admin default).
     * @param anonymousPaths Paths that skip validation (always include /health).
     */
    fun Application.installOidfJwtAuthIfConfigured(
        configBinder: OidfConfigBinder,
        requireAuth: Boolean,
        anonymousPaths: List<String> = listOf("/health"),
    ) {
        if (!shouldInstall(configBinder)) {
            logger.info(
                "JWT authentication not installed " +
                    "(mode=${configBinder.getIdentityConfig().mode}, " +
                    "jwtAuth=${configBinder.getOAuth2Config().jwtAuthEnabled}, " +
                    "issuerBlank=${configBinder.getOAuth2Config().issuerUri.isBlank()})",
            )
            return
        }
        val oauth = configBinder.getOAuth2Config()
        logger.info(
            "Installing IDK JwtAuthentication + post-JWT session rebind " +
                "(issuer=${oauth.issuerUri}, requireAuth=$requireAuth, audience=${oauth.audience})",
        )
        install(JwtAuthentication) {
            this.requireAuth = requireAuth
            this.anonymousPaths = anonymousPaths
            if (oauth.audience.isNotBlank()) {
                this.expectedAudience = oauth.audience
            }
        }
        // Stamp validated claims, then rebind DI session if JWT tenant diverged
        install(OidfStampValidatedJwtClaimsPlugin)
        install(OidfJwtSessionRebindPlugin)
        installOidfReboundSessionCleanup()
    }
}

/**
 * After [JwtAuthentication] builds a non-anonymous session, stamp
 * [ValidatedJwtClaimsAttribute] from the Authorization bearer payload.
 *
 * Safe only because [JwtAuthentication] already rejected invalid tokens
 * (or allowed anonymous paths without a session principal).
 */
val OidfStampValidatedJwtClaimsPlugin =
    createApplicationPlugin(name = "OidfStampValidatedJwtClaims") {
        onCall { call ->
            if (call.attributes.contains(ValidatedJwtClaimsAttribute)) return@onCall
            val session = call.attributes.getOrNull(SessionContextAttributeKey) ?: return@onCall
            if (session.isAnonymous()) return@onCall
            val auth = call.request.header(HttpHeaders.Authorization)
            val token = BearerTokenSupport.extractAccessToken(auth) ?: return@onCall
            val claimsInput = JwtClaimsParser.toJwtClaimsInput(token) ?: return@onCall
            call.attributes.put(ValidatedJwtClaimsAttribute, claimsInput.markValidated())
        }
    }

/**
 * Attribute holding a kotlin-inject [SessionInstance] created by post-JWT rebind.
 * Destroyed in [ApplicationCallPipeline.Fallback] after KotlinInjectPlugin has
 * torn down the bootstrap session.
 */
val OidfReboundSessionAttribute: AttributeKey<SessionInstance> =
    AttributeKey("oidf.auth.reboundSessionInstance")

/**
 * When validated JWT tenant claims differ from the bootstrap DI session tenant
 * (platform root / fixed), recreate user+session graphs via [UserContextInterceptor]
 * so SessionScope services see the JWT tenant.
 *
 * Requires [ValidatedJwtClaimsAttribute] (stamp plugin) and an existing
 * [UserContextInterceptor.RequestContextKey] from KotlinInjectPlugin.
 */
val OidfJwtSessionRebindPlugin =
    createApplicationPlugin(name = "OidfJwtSessionRebind") {
        onCall { call ->
            call.rebindKotlinInjectSessionToValidatedJwtTenant()
        }
    }

/**
 * Recreate the per-request kotlin-inject session when JWT tenant ≠ bootstrap tenant.
 *
 * @return true if a rebind was performed
 */
suspend fun ApplicationCall.rebindKotlinInjectSessionToValidatedJwtTenant(): Boolean {
    val validated = attributes.getOrNull(ValidatedJwtClaimsAttribute) ?: return false
    val jwtTenant = PlatformJwtTenantClaims.extractTenantId(validated.claimsInput.claims)
        ?.takeUnless { it.isAnonymousTenantId() }
        ?: return false

    val existing = attributes.getOrNull(UserContextInterceptor.RequestContextKey) ?: return false
    val bootstrapTenant = existing.userInstance.context.tenant.tenantId
    if (bootstrapTenant == jwtTenant) {
        logger.debug("DI session tenant already matches JWT tenant=$jwtTenant; skip rebind")
        return false
    }

    logger.info(
        "Rebinding kotlin-inject session after JWT: bootstrapTenant=$bootstrapTenant → jwtTenant=$jwtTenant",
    )

    // UserContextInterceptor requires BaseTenantIdAttribute to match re-resolved tenant
    // or be absent — clear bootstrap stamp so re-create can succeed.
    attributes.remove(BaseTenantIdAttribute)

    val inject = application.kotlinInject
    val interceptor = UserContextInterceptor(
        appGraph = inject.appGraph,
        tenantResolver = inject.tenantResolver,
        principalResolver = inject.principalResolver,
    )
    // OidfSessionTenantResolver now sees ValidatedJwtClaimsAttribute → JWT tenant
    val rebound = interceptor.intercept(this)

    // Bootstrap session is still destroyed by KotlinInjectPlugin.finally;
    // schedule destroy for the rebound session after the call fully completes.
    attributes.put(OidfReboundSessionAttribute, rebound.sessionInstance)
    return true
}

/**
 * Destroy rebound sessions after the request completes (after Plugins finally
 * has destroyed the bootstrap session).
 */
fun Application.installOidfReboundSessionCleanup() {
    intercept(ApplicationCallPipeline.Fallback) {
        try {
            proceed()
        } finally {
            call.attributes.getOrNull(OidfReboundSessionAttribute)?.let { session ->
                try {
                    session.destroy()
                    logger.debug("Destroyed post-JWT rebound session ${session.sessionId}")
                } catch (e: Exception) {
                    logger.warn("Failed to destroy rebound session: ${e.message}")
                }
            }
        }
    }
}

/**
 * Whether JWT auth should be considered active for the current binder config.
 */
fun OAuth2Config.isJwtAuthAutoEligible(mode: IdentityMode): Boolean =
    jwtAuthEnabled.trim().lowercase() in setOf("true", "1", "yes", "on") ||
        (jwtAuthEnabled.trim().lowercase() !in setOf("false", "0", "no", "off") &&
            mode == IdentityMode.PLATFORM &&
            issuerUri.isNotBlank())

private fun String.isAnonymousTenantId(): Boolean =
    this == IdentityConstants.ANONYMOUS_TENANT_ID || this.equals("anonymous", ignoreCase = true)
