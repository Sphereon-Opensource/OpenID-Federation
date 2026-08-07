package com.sphereon.openid.fed.server.federation.ktor.session

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
 * IDK session [TenantResolver] for the public federation server (JWT-first open).
 * ACCOUNT header entity switch is admin-only rebind; public stays JWT-first / root default.
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
            return DefaultTenantInputString(fromJwt)
        }
        return DefaultTenantInputString(fixedId)
    }

    private fun resolveAccountJwtFirst(call: ApplicationCall, fixedId: String): TenantInput {
        val fromJwt = tenantIdFromValidatedJwt(call)
        if (fromJwt != null) {
            return DefaultTenantInputString(fromJwt)
        }
        val rootId =
            LegacyAccountSessionTenantLookup.resolveAccountId(AccountEntityHeaderAuth.ROOT_USERNAME)
        if (rootId != null) {
            return DefaultTenantInputString(rootId)
        }
        logger.warn("ACCOUNT root Account missing; fixed session tenant '$fixedId'")
        return DefaultTenantInputString(fixedId)
    }

    private fun tenantIdFromValidatedJwt(call: ApplicationCall): String? {
        val validated = call.attributes.getOrNull(ValidatedJwtClaimsAttribute) ?: return null
        return PlatformJwtTenantClaims.extractTenantId(validated.claimsInput.claims)
    }
}
