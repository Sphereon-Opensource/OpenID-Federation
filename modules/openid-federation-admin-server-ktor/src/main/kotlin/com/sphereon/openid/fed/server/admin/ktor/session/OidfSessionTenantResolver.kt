package com.sphereon.openid.fed.server.admin.ktor.session

import com.sphereon.core.api.log.Log
import com.sphereon.core.defaults.context.DefaultTenantInputString
import com.sphereon.di.context.TenantInput
import com.sphereon.ktor.server.inject.ValidatedJwtClaimsAttribute
import com.sphereon.ktor.server.inject.resolver.FixedTenantResolver
import com.sphereon.ktor.server.inject.resolver.TenantResolver
import com.sphereon.openid.fed.account.LegacyAccountSessionTenantLookup
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.tenant.IdentityMode
import com.sphereon.openid.fed.core.tenant.PlatformJwtTenantClaims
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * IDK session [TenantResolver] with **L2 alignment** and **PLATFORM JWT** support.
 *
 * ## Modes
 * | Mode | Session tenant source |
 * |------|------------------------|
 * | LEGACY + ACCOUNT (L2) | `X-Account-Username` → Account.id |
 * | LEGACY + FIXED (L1) | `session.fixed.tenant.id` |
 * | PLATFORM | 1) validated JWT tenant claims 2) `platform.root.tenant.id` 3) fixed id |
 *
 * ## Security boundary
 * - PLATFORM never uses `X-Account-Username` / `X-Tenant-Id` as identity.
 * - JWT tenants are taken only from [ValidatedJwtClaimsAttribute] (host jwt-auth plugin
 *   must validate signature/iss/exp and stamp the attribute). Unvalidated bearer tokens
 *   are ignored here.
 *
 * ## Bootstrap vs rebind
 * KotlinInject opens the session **before** JWT claims are stamped, so the first
 * resolve often uses platform root / fixed. After JWT validation,
 * [com.sphereon.openid.fed.server.admin.ktor.auth.OidfJwtSessionRebindPlugin]
 * re-invokes this resolver with [ValidatedJwtClaimsAttribute] present and recreates
 * the DI graph when the JWT tenant differs.
 *
 * ## Fallback
 * Missing Account / missing JWT falls back to fixed tenant so the session graph can open;
 * mutations still fail closed via [com.sphereon.openid.fed.server.admin.api.http.command.AdminMutationGuard]
 * when PLATFORM + anonymous.
 */
class OidfSessionTenantResolver(
    private val configBinder: OidfConfigBinder,
) : TenantResolver {

    private val logger = Log.app().withTag("OidfSessionTenantResolver")

    override fun resolve(call: ApplicationCall): TenantInput {
        val identity = configBinder.getIdentityConfig()
        val fixedId = identity.sessionFixedTenantId.ifBlank { "default" }

        // L1 emergency / explicit fixed alignment
        if (!identity.isSessionAccountAligned) {
            return FixedTenantResolver(fixedId).resolve(call)
        }

        return when (identity.mode) {
            IdentityMode.PLATFORM -> resolvePlatform(call, fixedId)
            IdentityMode.LEGACY -> resolveLegacy(call, fixedId)
        }
    }

    /**
     * PLATFORM: prefer validated JWT tenant claims; else configured platform root / fixed.
     */
    private fun resolvePlatform(call: ApplicationCall, fixedId: String): TenantInput {
        val fromJwt = tenantIdFromValidatedJwt(call)
        if (fromJwt != null) {
            logger.debug("PLATFORM session tenant from validated JWT claims: $fromJwt")
            return DefaultTenantInputString(fromJwt)
        }

        val configured = configBinder.getIdentityConfig().platformRootTenantId
            ?.takeIf { it.isNotBlank() }
        if (configured != null) {
            logger.debug("PLATFORM session tenant from config platform.root.tenant.id: $configured")
            return DefaultTenantInputString(configured)
        }

        logger.debug(
            "PLATFORM session tenant fallback to fixed id '$fixedId' " +
                "(no validated JWT tenant claim; host should install jwt-auth or set platform.root.tenant.id)",
        )
        return DefaultTenantInputString(fixedId)
    }

    /**
     * LEGACY L2: align session tenant with Account.id for the header-selected username.
     */
    private fun resolveLegacy(call: ApplicationCall, fixedId: String): TenantInput {
        val username = LegacyAccountSessionTenantLookup.usernameFromHeaders { name ->
            call.request.header(name)
        }
        val accountId = LegacyAccountSessionTenantLookup.resolveAccountId(username)
        if (accountId != null) {
            logger.debug("L2 session tenant aligned: username=$username → accountId=$accountId")
            return DefaultTenantInputString(accountId)
        }
        logger.warn(
            "L2 account lookup failed for username=$username; " +
                "falling back to session tenant '$fixedId'",
        )
        return DefaultTenantInputString(fixedId)
    }

    /**
     * Read tenant from IDK-validated JWT attribute only.
     */
    private fun tenantIdFromValidatedJwt(call: ApplicationCall): String? {
        val validated = call.attributes.getOrNull(ValidatedJwtClaimsAttribute) ?: return null
        return PlatformJwtTenantClaims.extractTenantId(validated.claimsInput.claims)
    }
}
