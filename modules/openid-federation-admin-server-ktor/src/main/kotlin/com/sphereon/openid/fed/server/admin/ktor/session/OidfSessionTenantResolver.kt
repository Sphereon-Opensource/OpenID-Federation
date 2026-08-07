package com.sphereon.openid.fed.server.admin.ktor.session

import com.sphereon.core.api.log.Log
import com.sphereon.core.defaults.context.DefaultTenantInputString
import com.sphereon.di.context.TenantInput
import com.sphereon.ktor.server.inject.ValidatedJwtClaimsAttribute
import com.sphereon.ktor.server.inject.resolver.FixedTenantResolver
import com.sphereon.ktor.server.inject.resolver.TenantResolver
import com.sphereon.openid.fed.account.LegacyAccountSessionTenantLookup
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.tenant.AccountEntityHeaderAuth
import com.sphereon.openid.fed.core.tenant.IdentityMode
import com.sphereon.openid.fed.core.tenant.PlatformJwtTenantClaims
import io.ktor.server.application.ApplicationCall

/**
 * IDK session [TenantResolver] for the admin server — **JWT-first open**.
 *
 * | Mode | Session tenant at open |
 * |------|-------------------------|
 * | ACCOUNT | JWT tenant claim if present, else seeded **root** Account.id (header switch is post-open rebind) |
 * | EXTERNAL | Validated JWT tenant claims only |
 * | session.alignment=fixed | Fixed id (emergency) |
 *
 * `X-Account-Username` is **not** applied here. ACCOUNT header rebind runs after JWT
 * ([com.sphereon.openid.fed.server.admin.ktor.auth.OidfAccountSessionRebindPlugin]).
 */
class OidfSessionTenantResolver(
    private val configBinder: OidfConfigBinder,
) : TenantResolver {

    private val logger = Log.app().withTag("OidfSessionTenantResolver")

    override fun resolve(call: ApplicationCall): TenantInput {
        val identity = configBinder.getIdentityConfig()
        val fixedId = identity.sessionFixedTenantId.ifBlank { "default" }

        if (!identity.isSessionAccountAligned) {
            return FixedTenantResolver(fixedId).resolve(call)
        }

        return when (identity.mode) {
            IdentityMode.EXTERNAL -> resolveExternal(call, fixedId)
            IdentityMode.ACCOUNT -> resolveAccountJwtFirst(call, fixedId)
        }
    }

    private fun resolveExternal(call: ApplicationCall, fixedId: String): TenantInput {
        val fromJwt = tenantIdFromValidatedJwt(call)
        if (fromJwt != null) {
            logger.debug("EXTERNAL session tenant from validated JWT claims: $fromJwt")
            return DefaultTenantInputString(fromJwt)
        }
        // Bootstrap only (health). Protected routes fail closed later without a tenant claim.
        logger.debug(
            "EXTERNAL session tenant bootstrap fixed id '$fixedId' " +
                "(no JWT tenant claim yet / anonymous path)",
        )
        return DefaultTenantInputString(fixedId)
    }

    /**
     * JWT-first ACCOUNT open: token tenant if any, else root Account.
     * Header entity switch is applied only by the rebind plugin after JWT.
     */
    private fun resolveAccountJwtFirst(call: ApplicationCall, fixedId: String): TenantInput {
        val fromJwt = tenantIdFromValidatedJwt(call)
        if (fromJwt != null) {
            logger.debug("ACCOUNT session tenant from JWT claim at open: $fromJwt")
            return DefaultTenantInputString(fromJwt)
        }

        val rootId =
            LegacyAccountSessionTenantLookup.resolveAccountId(AccountEntityHeaderAuth.ROOT_USERNAME)
        if (rootId != null) {
            logger.debug("ACCOUNT session tenant at open = root Account.id=$rootId (JWT-first)")
            return DefaultTenantInputString(rootId)
        }

        logger.warn(
            "ACCOUNT root Account lookup failed; falling back to fixed session tenant '$fixedId'",
        )
        return DefaultTenantInputString(fixedId)
    }

    private fun tenantIdFromValidatedJwt(call: ApplicationCall): String? {
        val validated = call.attributes.getOrNull(ValidatedJwtClaimsAttribute) ?: return null
        return PlatformJwtTenantClaims.extractTenantId(validated.claimsInput.claims)
    }
}
