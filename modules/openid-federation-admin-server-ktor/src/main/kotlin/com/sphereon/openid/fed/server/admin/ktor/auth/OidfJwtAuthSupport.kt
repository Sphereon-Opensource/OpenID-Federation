package com.sphereon.openid.fed.server.admin.ktor.auth

import com.sphereon.core.api.log.Log
import com.sphereon.core.defaults.context.JwtClaimsParser
import com.sphereon.core.defaults.context.markValidated
import com.sphereon.ktor.server.inject.ValidatedJwtClaimsAttribute
import com.sphereon.ktor.server.jwt.JwtAuthentication
import com.sphereon.ktor.server.jwt.SessionContextAttributeKey
import com.sphereon.openid.fed.core.config.OAuth2Config
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.tenant.BearerTokenSupport
import com.sphereon.openid.fed.core.tenant.IdentityMode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.header

private val logger = Log.app().withTag("OidfJwtAuthSupport")

/**
 * Install IDK [JwtAuthentication] for PLATFORM (or forced) deployments.
 *
 * ## Plugin order (required)
 * 1. [com.sphereon.ktor.server.inject.KotlinInjectPlugin] — session graph + JwtValidationService
 * 2. [JwtAuthentication] — validates bearer, builds [SessionContextAttributeKey]
 * 3. [OidfStampValidatedJwtClaimsPlugin] — stamps [ValidatedJwtClaimsAttribute] from
 *    the already-validated token so tenant resolvers / interceptors can read claims
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
            "Installing IDK JwtAuthentication " +
                "(issuer=${oauth.issuerUri}, requireAuth=$requireAuth, audience=${oauth.audience})",
        )
        install(JwtAuthentication) {
            this.requireAuth = requireAuth
            this.anonymousPaths = anonymousPaths
            if (oauth.audience.isNotBlank()) {
                this.expectedAudience = oauth.audience
            }
        }
        // Stamp validated claims attribute after JWT plugin accepted the request
        install(OidfStampValidatedJwtClaimsPlugin)
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
 * Whether JWT auth should be considered active for the current binder config.
 */
fun OAuth2Config.isJwtAuthAutoEligible(mode: IdentityMode): Boolean =
    jwtAuthEnabled.trim().lowercase() in setOf("true", "1", "yes", "on") ||
        (jwtAuthEnabled.trim().lowercase() !in setOf("false", "0", "no", "off") &&
            mode == IdentityMode.PLATFORM &&
            issuerUri.isNotBlank())
