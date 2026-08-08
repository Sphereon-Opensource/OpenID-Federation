package com.sphereon.openid.fed.server.federation.ktor.auth

import com.sphereon.core.api.log.Log
import com.sphereon.core.defaults.context.JwtClaimsParser
import com.sphereon.core.defaults.context.markValidated
import com.sphereon.ktor.server.inject.ValidatedJwtClaimsAttribute
import com.sphereon.ktor.server.jwt.JwtAuthentication
import com.sphereon.ktor.server.jwt.SessionContextAttributeKey
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
 * Optional JWT for the public federation server.
 *
 * Public protocol stays open when [requireAuth] is false and [anonymousPaths] cover
 * federation endpoints. No session rebind — when a Bearer is present and validated,
 * claims may be stamped for optional authenticated flows after the DI session exists.
 *
 * For admin-style “claims before session open”, see the admin server’s pre-session stamp.
 */
object OidfJwtAuthSupport {

    fun shouldInstall(configBinder: OidfConfigBinder): Boolean {
        val oauth = configBinder.getOAuth2Config()
        return when (oauth.jwtAuthEnabled.trim().lowercase()) {
            "false", "0", "no", "off" -> false
            else -> oauth.issuerUri.isNotBlank()
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
            "Installing IDK JwtAuthentication " +
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
