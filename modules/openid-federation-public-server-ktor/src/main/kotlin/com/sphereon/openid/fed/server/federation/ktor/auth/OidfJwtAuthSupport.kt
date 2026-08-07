package com.sphereon.openid.fed.server.federation.ktor.auth

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
 * Federation public protocol stays open when [requireAuth] is false (default for this server).
 * Post-JWT DI session rebind matches admin-server (see that module’s KDoc for full boundary).
 */
object OidfJwtAuthSupport {

    fun shouldInstall(configBinder: OidfConfigBinder): Boolean {
        val oauth = configBinder.getOAuth2Config()
        val identity = configBinder.getIdentityConfig()
        return when (oauth.jwtAuthEnabled.trim().lowercase()) {
            "true", "1", "yes", "on" -> oauth.issuerUri.isNotBlank()
            "false", "0", "no", "off" -> false
            else -> identity.mode == IdentityMode.PLATFORM && oauth.issuerUri.isNotBlank()
        }
    }

    fun Application.installOidfJwtAuthIfConfigured(
        configBinder: OidfConfigBinder,
        requireAuth: Boolean,
        anonymousPaths: List<String> = listOf("/health"),
    ) {
        if (!shouldInstall(configBinder)) {
            logger.info(
                "JWT authentication not installed " +
                    "(mode=${configBinder.getIdentityConfig().mode}, " +
                    "jwtAuth=${configBinder.getOAuth2Config().jwtAuthEnabled})",
            )
            return
        }
        val oauth = configBinder.getOAuth2Config()
        logger.info(
            "Installing IDK JwtAuthentication + post-JWT session rebind " +
                "(issuer=${oauth.issuerUri}, requireAuth=$requireAuth)",
        )
        install(JwtAuthentication) {
            this.requireAuth = requireAuth
            this.anonymousPaths = anonymousPaths
            if (oauth.audience.isNotBlank()) {
                this.expectedAudience = oauth.audience
            }
        }
        install(OidfStampValidatedJwtClaimsPlugin)
        install(OidfJwtSessionRebindPlugin)
        installOidfReboundSessionCleanup()
    }
}

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

val OidfReboundSessionAttribute: AttributeKey<SessionInstance> =
    AttributeKey("oidf.auth.reboundSessionInstance")

val OidfJwtSessionRebindPlugin =
    createApplicationPlugin(name = "OidfJwtSessionRebind") {
        onCall { call ->
            call.rebindKotlinInjectSessionToValidatedJwtTenant()
        }
    }

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

    attributes.remove(BaseTenantIdAttribute)

    val inject = application.kotlinInject
    val interceptor = UserContextInterceptor(
        appGraph = inject.appGraph,
        tenantResolver = inject.tenantResolver,
        principalResolver = inject.principalResolver,
    )
    val rebound = interceptor.intercept(this)
    attributes.put(OidfReboundSessionAttribute, rebound.sessionInstance)
    return true
}

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

private fun String.isAnonymousTenantId(): Boolean =
    this == IdentityConstants.ANONYMOUS_TENANT_ID || this.equals("anonymous", ignoreCase = true)
