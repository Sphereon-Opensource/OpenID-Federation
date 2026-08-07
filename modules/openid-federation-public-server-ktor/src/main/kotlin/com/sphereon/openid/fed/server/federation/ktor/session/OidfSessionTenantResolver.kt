package com.sphereon.openid.fed.server.federation.ktor.session

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
 * IDK session [TenantResolver] for the public federation server (L2 + PLATFORM JWT).
 *
 * Same contract as the admin-server resolver. Path-based public entity selection still
 * uses [com.sphereon.openid.fed.core.tenant.TenantContextResolver] separately.
 *
 * @see com.sphereon.openid.fed.server.admin.ktor.session.OidfSessionTenantResolver
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
            IdentityMode.PLATFORM -> resolvePlatform(call, fixedId)
            IdentityMode.LEGACY -> resolveLegacy(call, fixedId)
        }
    }

    private fun resolvePlatform(call: ApplicationCall, fixedId: String): TenantInput {
        val fromJwt = tenantIdFromValidatedJwt(call)
        if (fromJwt != null) {
            logger.debug("PLATFORM session tenant from validated JWT claims: $fromJwt")
            return DefaultTenantInputString(fromJwt)
        }
        val configured = configBinder.getIdentityConfig().platformRootTenantId
            ?.takeIf { it.isNotBlank() }
        if (configured != null) {
            return DefaultTenantInputString(configured)
        }
        return DefaultTenantInputString(fixedId)
    }

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
            "L2 account lookup failed for username=$username; falling back to '$fixedId'",
        )
        return DefaultTenantInputString(fixedId)
    }

    private fun tenantIdFromValidatedJwt(call: ApplicationCall): String? {
        val validated = call.attributes.getOrNull(ValidatedJwtClaimsAttribute) ?: return null
        return PlatformJwtTenantClaims.extractTenantId(validated.claimsInput.claims)
    }
}
