package com.sphereon.openid.fed.server.admin.ktor.auth

import com.sphereon.core.api.app.CoreApiAppExtensionGraph
import com.sphereon.core.api.log.Log
import com.sphereon.core.defaults.context.DefaultPrincipalInputString
import com.sphereon.core.defaults.context.DefaultTenantInputString
import com.sphereon.core.defaults.context.JwtClaimsParser
import com.sphereon.core.defaults.context.markValidated
import com.sphereon.core.defaults.context.toSecuredDetails
import com.sphereon.di.context.IdentityMetadata
import com.sphereon.di.context.IdentityResolutionInput
import com.sphereon.di.context.IdentityResolutionResult
import com.sphereon.di.context.PrincipalType
import com.sphereon.di.context.ResolutionSource
import com.sphereon.ktor.server.inject.BaseTenantIdAttribute
import com.sphereon.ktor.server.inject.ValidatedJwtClaimsAttribute
import com.sphereon.ktor.server.inject.context.RequestScopedContext
import com.sphereon.ktor.server.inject.interceptor.UserContextInterceptor
import com.sphereon.ktor.server.jwt.JwtAuthentication
import com.sphereon.ktor.server.jwt.SessionContextAttributeKey
import com.sphereon.openid.fed.account.LegacyAccountSessionTenantLookup
import com.sphereon.openid.fed.core.config.OAuth2Config
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.tenant.AccountEntityHeaderAuth
import com.sphereon.openid.fed.core.tenant.BearerTokenSupport
import com.sphereon.openid.fed.core.tenant.IdentityMode
import com.sphereon.openid.fed.core.tenant.PlatformJwtTenantClaims
import com.sphereon.openid.fed.server.admin.ktor.di.AdminServerAppGraph
import dev.zacsweers.metro.asContribution
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val logger = Log.app().withTag("OidfJwtAuthSupport")

/**
 * Admin JWT auth (IDK only) — **no throwaway validation session**.
 *
 * ## Order (matches IDK BYO)
 * 1. [com.sphereon.ktor.server.inject.KotlinInjectPlugin] — bootstrap User+Session
 *    (fixed/root tenant; principal still anonymous until claims exist)
 * 2. [JwtAuthentication] — validate Bearer with **request** SessionScope
 *    [com.sphereon.oauth2.jwt.validation.JwtValidationService]
 * 3. [OidfPostJwtIdentityPlugin] — stamp [ValidatedJwtClaimsAttribute] from the
 *    already-validated token; EXTERNAL fail-closed without tenant claim; align
 *    DI session to JWT principal + tenant; ACCOUNT header rebind when allowed
 */
object OidfJwtAuthSupport {

    fun shouldInstall(configBinder: OidfConfigBinder): Boolean {
        val oauth = configBinder.getOAuth2Config()
        return when (oauth.jwtAuthEnabled.trim().lowercase()) {
            "false", "0", "no", "off" -> false
            else -> oauth.issuerUri.isNotBlank()
        }
    }

    /**
     * JwtAuthentication + post-JWT identity alignment.
     * Must install **after** [com.sphereon.ktor.server.inject.KotlinInjectPlugin].
     */
    fun Application.installOidfJwtAuthAfterSession(
        appGraph: AdminServerAppGraph,
        configBinder: OidfConfigBinder,
        requireAuth: Boolean = true,
        anonymousPaths: List<String> = listOf("/health", "/debug/**"),
    ) {
        if (!shouldInstall(configBinder)) return
        val oauth = configBinder.getOAuth2Config()
        logger.info(
            "Installing JwtAuthentication + post-JWT identity alignment " +
                "(issuer=${oauth.issuerUri}, requireAuth=$requireAuth, " +
                "mode=${configBinder.getIdentityConfig().mode})",
        )
        install(JwtAuthentication) {
            this.requireAuth = requireAuth
            this.anonymousPaths = anonymousPaths
            if (oauth.audience.isNotBlank()) {
                this.expectedAudience = oauth.audience
            }
        }
        install(
            OidfPostJwtIdentityPlugin(
                appGraph = appGraph,
                configBinder = configBinder,
                anonymousPaths = anonymousPaths,
            ),
        )
    }
}

/**
 * After [JwtAuthentication]: stamp claims, EXTERNAL tenant claim gate, session align + ACCOUNT rebind.
 */
@OptIn(ExperimentalUuidApi::class)
fun OidfPostJwtIdentityPlugin(
    appGraph: AdminServerAppGraph,
    configBinder: OidfConfigBinder,
    anonymousPaths: List<String>,
) = createApplicationPlugin(name = "OidfPostJwtIdentity") {
    onCall { call ->
        if (matchesAnonymousPath(call.request.path(), anonymousPaths)) return@onCall

        val jwtSession = call.attributes.getOrNull(SessionContextAttributeKey)
        if (jwtSession == null || jwtSession.isAnonymous()) {
            // JwtAuthentication already 401'd when requireAuth=true
            return@onCall
        }

        // Stamp claims only after JwtAuthentication accepted the token (signature/iss/exp).
        if (!call.attributes.contains(ValidatedJwtClaimsAttribute)) {
            val token =
                BearerTokenSupport.extractAccessToken(call.request.header(HttpHeaders.Authorization))
                    ?: return@onCall
            val claimsInput = JwtClaimsParser.toJwtClaimsInput(token) ?: return@onCall
            call.attributes.put(ValidatedJwtClaimsAttribute, claimsInput.markValidated())
        }

        val validated = call.attributes[ValidatedJwtClaimsAttribute]
        val claims = validated.claimsInput.claims
        val identity = configBinder.getIdentityConfig()
        val headerLookup: (String) -> String? = { name -> call.request.header(name) }

        if (identity.isExternal) {
            val jwtTenant = PlatformJwtTenantClaims.extractTenantId(claims)
            if (jwtTenant.isNullOrBlank()) {
                call.respondUnauthorized(
                    "EXTERNAL mode requires a tenant claim on the access token " +
                        "(tenant_id / tid / …). Impersonation must be expressed by the AS in the token.",
                )
                return@onCall
            }
            call.alignSessionToIdentity(
                appGraph = appGraph,
                targetTenantId = jwtTenant,
                claims = claims,
                principalClaim = identity.accountHeaderPrincipalClaim,
                validated = validated,
                reason = "EXTERNAL JWT tenant",
            )
            return@onCall
        }

        // ACCOUNT mode
        if (!identity.isAccount || !identity.isSessionAccountAligned) return@onCall

        if (AccountEntityHeaderAuth.hasEntitySelectionHeader(headerLookup)) {
            val denied =
                AccountEntityHeaderAuth.denyReasonIfHeaderForbidden(claims, headerLookup, identity)
            if (denied != null) {
                call.respondForbidden(denied)
                return@onCall
            }
            val entityUsername =
                AccountEntityHeaderAuth.entityUsernameFromHeaders(headerLookup)
                    ?: return@onCall
            val targetAccountId =
                LegacyAccountSessionTenantLookup.resolveAccountId(entityUsername)
            if (targetAccountId == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    """{"error":"not_found","error_description":"Unknown account username for entity selection: $entityUsername"}""",
                )
                return@onCall
            }
            call.alignSessionToIdentity(
                appGraph = appGraph,
                targetTenantId = targetAccountId,
                claims = claims,
                principalClaim = identity.accountHeaderPrincipalClaim,
                validated = validated,
                reason = "ACCOUNT header rebind username=$entityUsername",
            )
            return@onCall
        }

        // No header: align to JWT tenant claim if any, else seeded root Account.id.
        // Never demote an already-aligned Account UUID to bootstrap "default".
        val jwtTenant = PlatformJwtTenantClaims.extractTenantId(claims)
        val rootAccountId =
            LegacyAccountSessionTenantLookup.resolveAccountId(AccountEntityHeaderAuth.ROOT_USERNAME)
        val requestContext =
            call.attributes.getOrNull(UserContextInterceptor.RequestContextKey)
        val currentTenantId =
            requestContext?.sessionInstance?.sessionExecution?.tenantId
        val targetTenantId =
            jwtTenant
                ?: rootAccountId
                ?: currentTenantId?.takeUnless {
                    it.equals("default", ignoreCase = true) ||
                        it.equals("anonymous", ignoreCase = true)
                }
                ?: identity.sessionFixedTenantId.ifBlank { "default" }
        call.alignSessionToIdentity(
            appGraph = appGraph,
            targetTenantId = targetTenantId,
            claims = claims,
            principalClaim = identity.accountHeaderPrincipalClaim,
            validated = validated,
            reason = "ACCOUNT JWT-first entity",
        )
    }
}

@OptIn(ExperimentalUuidApi::class)
private suspend fun ApplicationCall.alignSessionToIdentity(
    appGraph: AdminServerAppGraph,
    targetTenantId: String,
    claims: Map<String, JsonElement>,
    principalClaim: String,
    validated: com.sphereon.core.defaults.context.ValidatedJwtClaimsInput,
    reason: String,
) {
    val requestContext =
        attributes.getOrNull(UserContextInterceptor.RequestContextKey) ?: return
    val currentTenantId = requestContext.sessionInstance.sessionExecution.tenantId
    val principalId =
        AccountEntityHeaderAuth.principalFromClaims(claims, principalClaim)
            ?: claims["sub"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: "authenticated"

    // Always realign when bootstrap session is still anonymous; otherwise skip if tenant matches.
    if (currentTenantId == targetTenantId &&
        !requestContext.sessionInstance.sessionExecution.isAnonymous()
    ) {
        logger.debug("Session already aligned ($reason tenant=$targetTenantId)")
        return
    }

    logger.info(
        "Align session: $currentTenantId → $targetTenantId ($reason, principal=$principalId)",
    )

    val core = appGraph.asContribution<CoreApiAppExtensionGraph>()
    val identityResolution =
        runCatching {
            core.identityResolutionPipeline.resolve(
                IdentityResolutionInput(tokenClaims = claims),
            )
        }.getOrNull()

    val resolvedPrincipalId =
        identityResolution?.principalId?.takeIf { it.isNotBlank() } ?: principalId

    val effectiveResolution =
        IdentityResolutionResult(
            tenantId = targetTenantId,
            principalId = resolvedPrincipalId,
            principalType = identityResolution?.principalType ?: PrincipalType.USER,
            metadata =
                identityResolution?.metadata
                    ?: IdentityMetadata(resolvedFrom = ResolutionSource.TOKEN),
        )

    requestContext.sessionInstance.destroy()

    val userInstance =
        core.userContextManager.createOrGetFromResolvedInputs(
            tenantInput = DefaultTenantInputString(targetTenantId),
            principalInput = DefaultPrincipalInputString(resolvedPrincipalId),
            identityResolution = effectiveResolution,
            makeActive = false,
        )

    val sessionId = Uuid.random().toString()
    val correlationId = request.header("X-Correlation-Id") ?: sessionId
    val sessionInstance =
        userInstance.sessionContextManager.createOrGetFromId(
            sessionId = sessionId,
            correlationId = correlationId,
            makeActive = false,
            secureDetails = validated.toSecuredDetails(),
            principalType = effectiveResolution.principalType,
        )

    attributes.put(
        UserContextInterceptor.RequestContextKey,
        RequestScopedContext(
            userInstance = userInstance,
            sessionInstance = sessionInstance,
        ),
    )
    attributes.put(BaseTenantIdAttribute, targetTenantId)
    logger.debug("Session aligned: session=${sessionInstance.sessionId} tenant=$targetTenantId")
}

fun OAuth2Config.isJwtAuthAutoEligible(mode: IdentityMode): Boolean =
    jwtAuthEnabled.trim().lowercase() !in setOf("false", "0", "no", "off") &&
        issuerUri.isNotBlank()

internal fun matchesAnonymousPath(
    path: String,
    patterns: List<String>,
): Boolean {
    val normalized = path.trimEnd('/').ifEmpty { "/" }
    return patterns.any { pattern ->
        when {
            pattern.endsWith("/**") -> {
                val prefix = pattern.removeSuffix("/**").trimEnd('/')
                normalized == prefix || normalized.startsWith("$prefix/")
            }
            pattern.endsWith("/*") -> {
                val prefix = pattern.removeSuffix("/*").trimEnd('/')
                val rest = normalized.removePrefix(prefix).removePrefix("/")
                rest.isNotEmpty() && !rest.contains('/')
            }
            else -> normalized == pattern.trimEnd('/').ifEmpty { "/" } || path == pattern
        }
    }
}

internal suspend fun ApplicationCall.respondUnauthorized(description: String) {
    val sanitized = description.replace('"', '\'')
    response.header(
        HttpHeaders.WWWAuthenticate,
        """Bearer error="invalid_token", error_description="$sanitized"""",
    )
    respond(HttpStatusCode.Unauthorized)
}

internal suspend fun ApplicationCall.respondForbidden(description: String) {
    respond(
        HttpStatusCode.Forbidden,
        """{"error":"forbidden","error_description":"${description.replace('"', '\'')}"}""",
    )
}
